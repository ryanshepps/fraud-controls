package com.fraudcontrols.testing

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
import com.fraudcontrols.persistence.DynamoDecisionAuditSink
import com.fraudcontrols.persistence.RedisVelocityFeatureStore
import com.fraudcontrols.rules.ComparisonOperator
import com.fraudcontrols.rules.RuleAction
import com.fraudcontrols.rules.RuleActionType
import com.fraudcontrols.rules.RuleCondition
import com.fraudcontrols.rules.RuleDefinition
import com.fraudcontrols.rules.RuleValue
import com.fraudcontrols.scoring.Scorer
import com.fraudcontrols.scoring.ScorerFeatureProvider
import com.fraudcontrols.streaming.DecisionSideEffectExecutor
import com.fraudcontrols.streaming.KafkaDecisionSideEffectConsumer
import com.fraudcontrols.streaming.KafkaDecisionSideEffectOutbox
import com.fraudcontrols.streaming.KafkaRawEventPublisher
import com.fraudcontrols.streaming.KafkaTransactionDecisionConsumer
import com.fraudcontrols.streaming.kafkaDecisionSideEffectConsumer
import com.fraudcontrols.streaming.kafkaStringConsumer
import com.fraudcontrols.streaming.kafkaStringProducer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
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

class RuntimePipelineIntegrationTest {
    @Test
    fun `consumes transaction from redpanda evaluates with redis velocity persists audit to dynamodb and publishes decision`() = runBlocking {
        val redpanda = RedpandaContainer(DockerImageName.parse("redpandadata/redpanda:v24.2.9"))
        val dynamodb = TestContainer("amazon/dynamodb-local:2.5.4")
            .withExposedPorts(8000)
            .waitingFor(Wait.forListeningPort())
        val redis = TestContainer("redis:7.4-alpine")
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort())
        var producer: KafkaProducer<String, String>? = null
        var transactionConsumer: KafkaTransactionDecisionConsumer? = null
        var sideEffectConsumer: KafkaDecisionSideEffectConsumer? = null
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
            val decisionSideEffectsTopic = "decision-side-effects-$suffix"
            val auditTable = "decision_audit_$suffix".replace("-", "_")

            createTopics(redpanda.bootstrapServers, transactionsTopic, decisionsTopic, ruleEvaluationsTopic, decisionSideEffectsTopic)
            val createdDynamoClient = dynamoClient(dynamodb)
            dynamoClient = createdDynamoClient
            createAuditTable(createdDynamoClient, auditTable)

            val createdRedisClient = JedisPooled(redis.host, redis.getMappedPort(6379))
            redisClient = createdRedisClient
            val velocityStore = RedisVelocityFeatureStore(createdRedisClient)
            val eventTime = Instant.parse("2026-01-01T12:00:00Z")
            velocityStore.recordSenderSend(
                senderId = CustomerId("sender-1"),
                eventId = "prior-send",
                occurredAt = eventTime.minusSeconds(60),
                amount = BigDecimal("15.00"),
            )
            assertTrue(createdRedisClient.ttl("velocity:sender:sender-1:sends") > 0)

            val createdProducer = kafkaStringProducer(redpanda.bootstrapServers)
            producer = createdProducer
            val auditSink = DynamoDecisionAuditSink(createdDynamoClient, auditTable)
            val processor = DecisionProcessor(
                engine = DecisionEngine(
                    FeatureResolver(
                        defaultEventFeatureProviders() +
                            defaultVelocityFeatureProviders(velocityStore) +
                            ScorerFeatureProvider(FixedScorer(score = 0.1)),
                    ),
                ),
                sideEffectSink = KafkaDecisionSideEffectOutbox(createdProducer, decisionSideEffectsTopic),
            )
            transactionConsumer = KafkaTransactionDecisionConsumer(
                consumer = kafkaStringConsumer(
                    bootstrapServers = redpanda.bootstrapServers,
                    groupId = "decision-runtime-$suffix",
                    topics = listOf(transactionsTopic),
                ),
                processor = processor,
                rules = { listOf(velocityRule()) },
                clock = Clock.fixed(Instant.parse("2026-01-01T12:00:05Z"), ZoneOffset.UTC),
            )
            sideEffectConsumer = KafkaDecisionSideEffectConsumer(
                consumer = kafkaDecisionSideEffectConsumer(
                    bootstrapServers = redpanda.bootstrapServers,
                    groupId = "decision-side-effects-$suffix",
                    topics = listOf(decisionSideEffectsTopic),
                ),
                executor = DecisionSideEffectExecutor(
                    auditSink = auditSink,
                    decisionPublisher = KafkaRawEventPublisher(createdProducer, decisionsTopic),
                    ruleEvaluationPublisher = KafkaRawEventPublisher(createdProducer, ruleEvaluationsTopic),
                ),
            )

            createdProducer.send(ProducerRecord(transactionsTopic, "evt-1", fraudgenPayload())).get()
            createdProducer.flush()

            eventually("transaction consumed") {
                true.takeIf { transactionConsumer.pollAndProcess(Duration.ofMillis(250)) == 1 }
            }
            eventually("decision side effects executed") {
                true.takeIf { sideEffectConsumer.pollAndExecute(Duration.ofMillis(250)) == 1 }
            }

            val auditItem = eventually("audit item persisted") {
                createdDynamoClient.getItem { request ->
                    request.tableName(auditTable)
                        .key(mapOf("event_id" to software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder().s("evt-1").build()))
                }.item().takeIf { it.isNotEmpty() }
            }
            assertEquals("HOLD", auditItem["action"]?.s())
            assertEquals("1", auditItem["schema_version"]?.n())
            assertEquals("0.1", auditItem["score"]?.n())
            assertEquals("fixed-v1", auditItem["model_version"]?.s())
            assertEquals(listOf("velocity_spike"), auditItem["reason_codes"]?.l()?.map { it.s() })
            val auditRuleEvaluationJson = Json.parseToJsonElement(auditItem["rule_evaluation_json"]?.s().orEmpty()).jsonObject
            assertEquals("1", auditRuleEvaluationJson["schema_version"]?.jsonPrimitive?.content)
            assertEquals("velocity-spike", auditRuleEvaluationJson["matches"]?.jsonArray?.single()?.jsonObject?.get("rule_id")?.jsonPrimitive?.content)
            assertEquals("MATCHED", auditRuleEvaluationJson["evaluations"]?.jsonArray?.single()?.jsonObject?.get("condition_result")?.jsonPrimitive?.content)
            assertEquals("velocity-spike", auditRuleEvaluationJson["conflict_resolution"]?.jsonObject?.get("selected")?.jsonObject?.get("rule_id")?.jsonPrimitive?.content)

            val decisionPayload = readKafkaValue(redpanda.bootstrapServers, decisionsTopic, "evt-1")
            val decisionJson = Json.parseToJsonElement(decisionPayload).jsonObject
            assertEquals("1", decisionJson["schema_version"]?.jsonPrimitive?.content)
            assertEquals("evt-1", decisionJson["event_id"]?.jsonPrimitive?.content)
            assertEquals("HOLD", decisionJson["action"]?.jsonPrimitive?.content)
            assertEquals("velocity_spike", decisionJson["reason_codes"]?.jsonArray?.single()?.jsonPrimitive?.content)
            assertEquals("0.1", decisionJson["score"]?.jsonObject?.get("score")?.jsonPrimitive?.content)

            val ruleEvaluationPayload = readKafkaValue(redpanda.bootstrapServers, ruleEvaluationsTopic, "evt-1")
            val ruleEvaluationJson = Json.parseToJsonElement(ruleEvaluationPayload).jsonObject
            assertEquals("1", ruleEvaluationJson["schema_version"]?.jsonPrimitive?.content)
            assertEquals("velocity-spike", ruleEvaluationJson["matches"]?.jsonArray?.single()?.jsonObject?.get("rule_id")?.jsonPrimitive?.content)
            assertEquals("MATCHED", ruleEvaluationJson["evaluations"]?.jsonArray?.single()?.jsonObject?.get("condition_result")?.jsonPrimitive?.content)
        } finally {
            transactionConsumer?.close()
            sideEffectConsumer?.close()
            producer?.close()
            redisClient?.close()
            dynamoClient?.close()
            redis.stop()
            dynamodb.stop()
            redpanda.stop()
        }
    }

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

    private suspend fun readKafkaValue(
        bootstrapServers: String,
        topic: String,
        key: String,
    ): String {
        val consumer = kafkaStringConsumer(
            bootstrapServers = bootstrapServers,
            groupId = "assert-$topic-${UUID.randomUUID()}",
            topics = listOf(topic),
        )
        return consumer.use {
            eventually("kafka record $topic/$key") {
                consumer.poll(Duration.ofMillis(250))
                    .firstOrNull { it.key() == key }
                    ?.value()
            }
        }
    }

    private suspend fun <T : Any> eventually(
        description: String,
        timeout: Duration = Duration.ofSeconds(20),
        block: suspend () -> T?,
    ): T {
        val deadline = System.nanoTime() + timeout.toNanos()
        var lastValue: T? = null
        while (System.nanoTime() < deadline) {
            lastValue = block()
            if (lastValue != null) {
                return lastValue
            }
            kotlinx.coroutines.delay(100)
        }
        error("Timed out waiting for $description; last value=$lastValue")
    }

    private fun velocityRule(): RuleDefinition = RuleDefinition(
        id = "velocity-spike",
        version = 1,
        priority = 100,
        condition = RuleCondition.Comparison(
            featureName = FraudFeatureNames.SENDER_SEND_COUNT_5M,
            operator = ComparisonOperator.GTE,
            value = RuleValue.NumberValue(1.0),
        ),
        action = RuleAction(
            type = RuleActionType.REVIEW_QUEUE,
            reasonCode = ReasonCode("velocity_spike"),
            queue = "trust_safety_l2",
        ),
    )

    private fun fraudgenPayload(): String =
        """
        {
          "event_id": "evt-1",
          "timestamp": "2026-01-01T12:00:00+00:00",
          "sender_id": "sender-1",
          "recipient_id": "recipient-1",
          "amount": 25.50,
          "currency": "USD",
          "type": "p2p_send",
          "sender_device_fingerprint": "device-1",
          "sender_geo": {"lat": 43.6532, "lng": -79.3832},
          "sender_balance_before": 100.00,
          "sender_balance_after": 74.50,
          "recipient_balance_before": 50.00,
          "recipient_balance_after": 75.50,
          "sender_account_age_days": 0.125,
          "recipient_account_age_days": 120.5,
          "is_new_counterparty": true
        }
        """.trimIndent()
}

private class FixedScorer(
    private val score: Double,
) : Scorer {
    override val name: String = "fixed"
    override val version: String = "fixed-v1"

    override suspend fun score(context: ScoringContext): ScoreResult = ScoreResult(
        score = score,
        rawScore = null,
        contributingFactors = listOf(Factor(name = "fixed", contribution = score)),
        modelVersion = version,
        latencyMs = 1.0,
    )
}

private class TestContainer(
    imageName: String,
) : GenericContainer<TestContainer>(DockerImageName.parse(imageName))
