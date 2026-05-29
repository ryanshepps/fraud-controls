package com.fraudcontrols.testing

import com.fraudcontrols.api.KafkaRuleChangeAuditPublisher
import com.fraudcontrols.api.RuleAdminService
import com.fraudcontrols.core.CustomerId
import com.fraudcontrols.core.Factor
import com.fraudcontrols.core.ReasonCode
import com.fraudcontrols.core.ScoreResult
import com.fraudcontrols.core.ScoringContext
import com.fraudcontrols.decisioning.DecisionEngine
import com.fraudcontrols.decisioning.DecisionProcessor
import com.fraudcontrols.features.FeatureResolver
import com.fraudcontrols.features.FraudFeatureNames
import com.fraudcontrols.features.defaultEventFeatureProviders
import com.fraudcontrols.features.defaultVelocityFeatureProviders
import com.fraudcontrols.observability.DecisioningMetricsAdapter
import com.fraudcontrols.observability.KafkaShadowEvaluationReporterConsumer
import com.fraudcontrols.observability.KafkaShadowEvaluationSink
import com.fraudcontrols.observability.MicrometerControlsMetrics
import com.fraudcontrols.observability.ShadowEvaluationReporter
import com.fraudcontrols.observability.kafkaShadowReporterConsumer
import com.fraudcontrols.persistence.DynamoDecisionAuditSink
import com.fraudcontrols.persistence.RedisVelocityFeatureStore
import com.fraudcontrols.rules.ComparisonOperator
import com.fraudcontrols.rules.RuleAction
import com.fraudcontrols.rules.RuleActionType
import com.fraudcontrols.rules.RuleCondition
import com.fraudcontrols.rules.RuleDefinition
import com.fraudcontrols.rules.RuleMode
import com.fraudcontrols.rules.RuleValue
import com.fraudcontrols.scoring.Scorer
import com.fraudcontrols.scoring.ScorerFeatureProvider
import com.fraudcontrols.scoring.ShadowScorer
import com.fraudcontrols.streaming.KafkaDecisionPublisher
import com.fraudcontrols.streaming.KafkaRuleEvaluationPublisher
import com.fraudcontrols.streaming.KafkaTransactionDecisionConsumer
import com.fraudcontrols.streaming.kafkaStringConsumer
import com.fraudcontrols.streaming.kafkaStringProducer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.redpanda.RedpandaContainer
import org.testcontainers.utility.DockerImageName
import redis.clients.jedis.JedisPooled
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.BillingMode
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType
import java.math.BigDecimal
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Properties
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalDemoValidationTest {
    @Test
    fun `validates local demo rule lifecycle ingestion audit and dashboard metrics`() {
        runBlocking {
            val redpanda = RedpandaContainer(DockerImageName.parse("redpandadata/redpanda:v24.2.9"))
            val dynamodb = DemoTestContainer("amazon/dynamodb-local:2.5.4")
                .withExposedPorts(8000)
                .waitingFor(Wait.forListeningPort())
            val redis = DemoTestContainer("redis:7.4-alpine")
                .withExposedPorts(6379)
                .waitingFor(Wait.forListeningPort())
            var producer: KafkaProducer<String, String>? = null
            var transactionConsumer: KafkaTransactionDecisionConsumer? = null
            var reporterConsumer: Consumer<String, String>? = null
            var redisClient: JedisPooled? = null
            var dynamoClient: DynamoDbClient? = null

            redpanda.start()
            dynamodb.start()
            redis.start()
            try {
                val suffix = UUID.randomUUID().toString()
                val transactionsTopic = "transactions-$suffix"
                val decisionsTopic = "decisions-$suffix"
                val ruleEvaluationsTopic = "rule-evaluations-$suffix"
                val shadowEvaluationsTopic = "shadow-evaluations-$suffix"
                val ruleChangesTopic = "rule-changes-$suffix"
                val auditTable = "decision_audit_$suffix".replace("-", "_")

                createTopics(
                    redpanda.bootstrapServers,
                    transactionsTopic,
                    decisionsTopic,
                    ruleEvaluationsTopic,
                    shadowEvaluationsTopic,
                    ruleChangesTopic,
                )
                val createdDynamoClient = dynamoClient(dynamodb)
                dynamoClient = createdDynamoClient
                createAuditTable(createdDynamoClient, auditTable)

                val createdRedisClient = JedisPooled(redis.host, redis.getMappedPort(6379))
                redisClient = createdRedisClient
                val velocityStore = RedisVelocityFeatureStore(createdRedisClient)
                val eventTime = Instant.parse("2026-01-01T12:00:00Z")
                velocityStore.recordSenderSend(
                    senderId = CustomerId("sender-1"),
                    eventId = "prior-demo-send",
                    occurredAt = eventTime.minusSeconds(30),
                    amount = BigDecimal("25.00"),
                )
                assertTrue(createdRedisClient.ttl("velocity:sender:sender-1:sends") > 0)

                val createdProducer = kafkaStringProducer(redpanda.bootstrapServers)
                producer = createdProducer
                val ruleAdminService = RuleAdminService(
                    auditPublisher = KafkaRuleChangeAuditPublisher(createdProducer, ruleChangesTopic),
                    clock = Clock.fixed(eventTime, ZoneOffset.UTC),
                )
                var activeRules = emptyList<RuleDefinition>()
                val controlsMetrics = MicrometerControlsMetrics()
                val metricsAdapter = DecisioningMetricsAdapter(controlsMetrics)
                val shadowScorer = ShadowScorer(
                    name = "demo_shadow_runner",
                    primary = DemoFixedScorer(name = "demo_live", version = "demo-live-v1", score = 0.2),
                    shadows = listOf(DemoFixedScorer(name = "demo_candidate", version = "demo-candidate-v1", score = 0.9)),
                    sink = KafkaShadowEvaluationSink(createdProducer, shadowEvaluationsTopic),
                )
                val processor = DecisionProcessor(
                    engine = DecisionEngine(
                        FeatureResolver(
                            defaultEventFeatureProviders() +
                                defaultVelocityFeatureProviders(velocityStore) +
                                ScorerFeatureProvider(shadowScorer),
                        ),
                        metrics = metricsAdapter,
                    ),
                    auditSink = DynamoDecisionAuditSink(createdDynamoClient, auditTable),
                    decisionPublisher = KafkaDecisionPublisher(createdProducer, decisionsTopic),
                    ruleEvaluationPublisher = KafkaRuleEvaluationPublisher(createdProducer, ruleEvaluationsTopic),
                    metrics = metricsAdapter,
                )
                transactionConsumer = KafkaTransactionDecisionConsumer(
                    consumer = kafkaStringConsumer(
                        bootstrapServers = redpanda.bootstrapServers,
                        groupId = "demo-decision-runtime-$suffix",
                        topics = listOf(transactionsTopic),
                    ),
                    processor = processor,
                    rules = { activeRules },
                    clock = Clock.fixed(eventTime.plusSeconds(5), ZoneOffset.UTC),
                )
                reporterConsumer = kafkaShadowReporterConsumer(
                    bootstrapServers = redpanda.bootstrapServers,
                    groupId = "demo-observability-$suffix",
                    topics = listOf(shadowEvaluationsTopic, ruleEvaluationsTopic),
                )
                val reporter = KafkaShadowEvaluationReporterConsumer(
                    consumer = reporterConsumer,
                    reporter = ShadowEvaluationReporter(controlsMetrics),
                    shadowEvaluationsTopic = shadowEvaluationsTopic,
                    ruleEvaluationsTopic = ruleEvaluationsTopic,
                )

                ruleAdminService.create(demoScoreRule(mode = RuleMode.SHADOW, enabled = true), actor = "demo")
                activeRules = ruleAdminService.list()
                processTransaction(createdProducer, transactionConsumer, transactionsTopic, "evt-shadow")

                ruleAdminService.promote("demo-score-shadow", confirmed = true, actor = "demo")
                activeRules = ruleAdminService.list()
                processTransaction(createdProducer, transactionConsumer, transactionsTopic, "evt-enforce")

                ruleAdminService.disable("demo-score-shadow", actor = "demo")
                activeRules = ruleAdminService.list()
                processTransaction(createdProducer, transactionConsumer, transactionsTopic, "evt-disabled")

                val decisions = readKafkaValues(
                    bootstrapServers = redpanda.bootstrapServers,
                    topic = decisionsTopic,
                    keys = setOf("evt-shadow", "evt-enforce", "evt-disabled"),
                ).mapValues { (_, payload) ->
                    Json.parseToJsonElement(payload).jsonObject["action"]?.jsonPrimitive?.content
                }
                assertEquals("ALLOW", decisions["evt-shadow"])
                assertEquals("DENY", decisions["evt-enforce"])
                assertEquals("ALLOW", decisions["evt-disabled"])

                val auditItem = eventually("enforced audit row") {
                    createdDynamoClient.getItem { request ->
                        request.tableName(auditTable)
                            .key(mapOf("event_id" to AttributeValue.builder().s("evt-enforce").build()))
                    }.item().takeIf { it.isNotEmpty() }
                }
                assertEquals("DENY", auditItem["action"]?.s())
                assertEquals("0.2", auditItem["score"]?.n())
                assertEquals("demo-live-v1", auditItem["model_version"]?.s())
                assertEquals(listOf("demo_score_rule"), auditItem["reason_codes"]?.l()?.map { it.s() })
                val featuresJson = Json.parseToJsonElement(auditItem["features_json"]?.s().orEmpty()).jsonObject
                val featureValues = featuresJson["values"]?.jsonObject.orEmpty()
                assertTrue(featureValues.containsKey(FraudFeatureNames.FRAUD_MODEL_SCORE))
                assertTrue(featureValues.containsKey(FraudFeatureNames.SENDER_SEND_COUNT_5M))

                val ruleChanges = readKafkaRecords(
                    bootstrapServers = redpanda.bootstrapServers,
                    topic = ruleChangesTopic,
                    expectedCount = 3,
                ).map {
                    Json.parseToJsonElement(it).jsonObject["change_type"]?.jsonPrimitive?.content
                }
                assertEquals(listOf("create", "promote", "disable"), ruleChanges)

                var reported = 0
                eventually("dashboard metrics updated") {
                    reported += reporter.pollAndReport(Duration.ofMillis(250))
                    controlsMetrics.scrape().takeIf {
                        reported >= 6 &&
                            it.contains("controls_decisions_total") &&
                            it.contains("action=\"ALLOW\"") &&
                            it.contains("action=\"DENY\"") &&
                            it.contains("controls_rule_fire_total") &&
                            it.contains("rule_id=\"demo-score-shadow\"") &&
                            it.contains("controls_shadow_rule_would_have_blocked_rate") &&
                            it.contains("controls_scorer_score_divergence") &&
                            it.contains("primary_scorer=\"demo_live\"") &&
                            it.contains("shadow_scorer=\"demo_candidate\"")
                    }
                }
            } finally {
                reporterConsumer?.close()
                transactionConsumer?.close()
                producer?.close()
                redisClient?.close()
                dynamoClient?.close()
                redis.stop()
                dynamodb.stop()
                redpanda.stop()
            }
        }
    }

    private suspend fun processTransaction(
        producer: KafkaProducer<String, String>,
        consumer: KafkaTransactionDecisionConsumer,
        topic: String,
        eventId: String,
    ) {
        producer.send(ProducerRecord(topic, eventId, fraudgenPayload(eventId))).get()
        producer.flush()
        eventually("transaction $eventId consumed") {
            true.takeIf { consumer.pollAndProcess(Duration.ofMillis(250)) == 1 }
        }
    }

    private fun demoScoreRule(
        mode: RuleMode,
        enabled: Boolean,
    ): RuleDefinition = RuleDefinition(
        id = "demo-score-shadow",
        version = 1,
        description = "Demo score and velocity rule",
        enabled = enabled,
        mode = mode,
        priority = 100,
        condition = RuleCondition.All(
            listOf(
                RuleCondition.Comparison(
                    featureName = FraudFeatureNames.FRAUD_MODEL_SCORE,
                    operator = ComparisonOperator.GTE,
                    value = RuleValue.NumberValue(0.1),
                ),
                RuleCondition.Comparison(
                    featureName = FraudFeatureNames.SENDER_SEND_COUNT_5M,
                    operator = ComparisonOperator.GTE,
                    value = RuleValue.NumberValue(1.0),
                ),
            ),
        ),
        action = RuleAction(
            type = RuleActionType.BLOCK,
            reasonCode = ReasonCode("demo_score_rule"),
        ),
    )

    private fun fraudgenPayload(eventId: String): String =
        """
        {
          "event_id": "$eventId",
          "timestamp": "2026-01-01T12:00:00+00:00",
          "sender_id": "sender-1",
          "recipient_id": "recipient-1",
          "amount": 125.00,
          "currency": "USD",
          "type": "p2p_send",
          "sender_device_fingerprint": "device-1",
          "sender_geo": {"lat": 43.6532, "lng": -79.3832},
          "sender_balance_before": 250.00,
          "sender_balance_after": 125.00,
          "recipient_balance_before": 50.00,
          "recipient_balance_after": 175.00,
          "sender_account_age_days": 0.25,
          "recipient_account_age_days": 120.5,
          "is_new_counterparty": true
        }
        """.trimIndent()

    private fun createTopics(
        bootstrapServers: String,
        vararg topics: String,
    ) {
        AdminClient.create(
            Properties().apply {
                put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
            },
        ).use { admin ->
            admin.createTopics(topics.map { NewTopic(it, 1, 1) }).all().get()
        }
    }

    private fun createAuditTable(
        dynamoClient: DynamoDbClient,
        tableName: String,
    ) {
        dynamoClient.createTable(
            CreateTableRequest.builder()
                .tableName(tableName)
                .attributeDefinitions(
                    AttributeDefinition.builder()
                        .attributeName("event_id")
                        .attributeType(ScalarAttributeType.S)
                        .build(),
                )
                .keySchema(
                    KeySchemaElement.builder()
                        .attributeName("event_id")
                        .keyType(KeyType.HASH)
                        .build(),
                )
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build(),
        )
        dynamoClient.waiter().waitUntilTableExists { it.tableName(tableName) }
    }

    private fun dynamoClient(container: GenericContainer<*>): DynamoDbClient = DynamoDbClient.builder()
        .endpointOverride(URI.create("http://${container.host}:${container.getMappedPort(8000)}"))
        .region(Region.US_EAST_1)
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create("dummy", "dummy")),
        )
        .build()

    private suspend fun readKafkaValues(
        bootstrapServers: String,
        topic: String,
        keys: Set<String>,
    ): Map<String, String> {
        val records = linkedMapOf<String, String>()
        val consumer = kafkaStringConsumer(
            bootstrapServers = bootstrapServers,
            groupId = "assert-$topic-${UUID.randomUUID()}",
            topics = listOf(topic),
        )
        return consumer.use {
            eventually("kafka records $topic/$keys") {
                for (record in consumer.poll(Duration.ofMillis(250))) {
                    if (record.key() in keys) {
                        records[record.key()] = record.value()
                    }
                }
                records.takeIf { it.keys.containsAll(keys) }
            }
        }
    }

    private suspend fun readKafkaRecords(
        bootstrapServers: String,
        topic: String,
        expectedCount: Int,
    ): List<String> {
        val records = mutableListOf<String>()
        val consumer = kafkaStringConsumer(
            bootstrapServers = bootstrapServers,
            groupId = "assert-$topic-${UUID.randomUUID()}",
            topics = listOf(topic),
        )
        return consumer.use {
            eventually("kafka records $topic") {
                records += consumer.poll(Duration.ofMillis(250)).map { it.value() }
                records.takeIf { it.size >= expectedCount }?.take(expectedCount)
            }
        }
    }

    private suspend fun <T : Any> eventually(
        description: String,
        timeout: Duration = Duration.ofSeconds(30),
        block: suspend () -> T?,
    ): T {
        val deadline = System.nanoTime() + timeout.toNanos()
        var lastValue: T? = null
        while (System.nanoTime() < deadline) {
            lastValue = block()
            if (lastValue != null) {
                return lastValue
            }
            delay(100)
        }
        error("Timed out waiting for $description; last value=$lastValue")
    }
}

private class DemoFixedScorer(
    override val name: String,
    override val version: String,
    private val score: Double,
) : Scorer {
    override suspend fun score(context: ScoringContext): ScoreResult = ScoreResult(
        score = score,
        rawScore = score,
        contributingFactors = listOf(Factor(name = "fixed_demo_score", contribution = score)),
        modelVersion = version,
        latencyMs = 1.0,
    )
}

private class DemoTestContainer(
    imageName: String,
) : GenericContainer<DemoTestContainer>(DockerImageName.parse(imageName))
