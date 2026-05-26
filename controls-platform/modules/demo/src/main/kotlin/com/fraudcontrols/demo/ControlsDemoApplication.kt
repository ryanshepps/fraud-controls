package com.fraudcontrols.demo

import com.fraudcontrols.api.InMemoryDecisionRecordStore
import com.fraudcontrols.api.KafkaRuleChangeAuditPublisher
import com.fraudcontrols.api.RuleAdminService
import com.fraudcontrols.api.startAdminHttpServer
import com.fraudcontrols.core.Factor
import com.fraudcontrols.core.ReasonCode
import com.fraudcontrols.core.ScoreResult
import com.fraudcontrols.core.ScoringContext
import com.fraudcontrols.decisioning.DecisionAuditSink
import com.fraudcontrols.decisioning.DecisionEngine
import com.fraudcontrols.decisioning.DecisionProcessor
import com.fraudcontrols.decisioning.DecisionRecord
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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Properties
import java.util.UUID
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
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
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType

fun main() {
    runBlocking {
        val config = DemoRuntimeConfig.fromEnvironment()
        println("starting controls demo runtime with kafka=${config.kafkaBootstrapServers}")

        retry("kafka topics") {
            createTopics(
                bootstrapServers = config.kafkaBootstrapServers,
                topics = listOf(
                    config.transactionsTopic,
                    config.decisionsTopic,
                    config.ruleEvaluationsTopic,
                    config.shadowEvaluationsTopic,
                    config.ruleChangesTopic,
                    config.fraudLabelsTopic,
                ),
            )
        }

        val dynamoClient = dynamoClient(config.dynamoEndpoint)
        ensureAuditTable(dynamoClient, config.auditTable)
        val redisClient = JedisPooled(config.redisHost, config.redisPort)
        val velocityStore = RedisVelocityFeatureStore(redisClient)
        val producer = kafkaStringProducer(config.kafkaBootstrapServers)
        val controlsMetrics = MicrometerControlsMetrics()
        val metricsAdapter = DecisioningMetricsAdapter(controlsMetrics)
        val decisionRecordStore = InMemoryDecisionRecordStore()
        val ruleAdminService = RuleAdminService(
            initialRules = demoRules(),
            auditPublisher = KafkaRuleChangeAuditPublisher(producer, config.ruleChangesTopic),
        )

        val scorer = ShadowScorer(
            name = "demo_sidecar_shadow_runner",
            primary = DemoSidecarScorer(config.scoringSidecarUrl),
            shadows = listOf(DemoCandidateScorer()),
            sink = KafkaShadowEvaluationSink(producer, config.shadowEvaluationsTopic),
        )
        val processor = DecisionProcessor(
            engine = DecisionEngine(
                featureResolver = FeatureResolver(
                    defaultEventFeatureProviders() +
                        defaultVelocityFeatureProviders(velocityStore) +
                        ScorerFeatureProvider(scorer),
                ),
                metrics = metricsAdapter,
            ),
            auditSink = FanOutDecisionAuditSink(
                DynamoDecisionAuditSink(dynamoClient, config.auditTable),
                decisionRecordStore,
            ),
            decisionPublisher = KafkaDecisionPublisher(producer, config.decisionsTopic),
            ruleEvaluationPublisher = KafkaRuleEvaluationPublisher(producer, config.ruleEvaluationsTopic),
            metrics = metricsAdapter,
        )
        val transactionConsumer = KafkaTransactionDecisionConsumer(
            consumer = kafkaStringConsumer(
                bootstrapServers = config.kafkaBootstrapServers,
                groupId = "controls-demo-runtime",
                topics = listOf(config.transactionsTopic),
            ),
            processor = processor,
            rules = { runBlocking { ruleAdminService.list() } },
            beforeProcess = { event ->
                velocityStore.recordSenderSend(
                    senderId = event.senderId,
                    eventId = event.eventId.value,
                    occurredAt = event.timestamp,
                    amount = event.amount.amount,
                )
            },
        )
        val reporterConsumer = KafkaShadowEvaluationReporterConsumer(
            consumer = kafkaShadowReporterConsumer(
                bootstrapServers = config.kafkaBootstrapServers,
                groupId = "controls-demo-observability",
                topics = listOf(config.shadowEvaluationsTopic, config.ruleEvaluationsTopic),
            ),
            reporter = ShadowEvaluationReporter(controlsMetrics),
            shadowEvaluationsTopic = config.shadowEvaluationsTopic,
            ruleEvaluationsTopic = config.ruleEvaluationsTopic,
        )

        startAdminHttpServer(
            ruleAdminService = ruleAdminService,
            decisionRecords = decisionRecordStore,
            host = "0.0.0.0",
            port = config.httpPort,
            metricsScrape = controlsMetrics::scrape,
        )
        println("admin API ready on :${config.httpPort}; metrics at /metrics")

        launch(Dispatchers.IO) {
            while (isActive) {
                val processed = transactionConsumer.pollAndProcess(Duration.ofMillis(500))
                if (processed > 0) {
                    println("processed $processed transaction(s)")
                }
            }
        }
        launch(Dispatchers.IO) {
            while (isActive) {
                reporterConsumer.pollAndReport(Duration.ofMillis(500))
            }
        }
        Runtime.getRuntime().addShutdownHook(
            Thread {
                transactionConsumer.close()
                reporterConsumer.close()
                producer.close()
                redisClient.close()
                dynamoClient.close()
            },
        )
        awaitCancellation()
    }
}

private data class DemoRuntimeConfig(
    val kafkaBootstrapServers: String,
    val transactionsTopic: String,
    val decisionsTopic: String,
    val ruleEvaluationsTopic: String,
    val shadowEvaluationsTopic: String,
    val ruleChangesTopic: String,
    val fraudLabelsTopic: String,
    val dynamoEndpoint: String,
    val auditTable: String,
    val redisHost: String,
    val redisPort: Int,
    val scoringSidecarUrl: String,
    val httpPort: Int,
) {
    companion object {
        fun fromEnvironment(): DemoRuntimeConfig =
            DemoRuntimeConfig(
                kafkaBootstrapServers = env("KAFKA_BOOTSTRAP_SERVERS", "localhost:19092"),
                transactionsTopic = env("TRANSACTIONS_TOPIC", "transactions"),
                decisionsTopic = env("DECISIONS_TOPIC", "controls.decisions"),
                ruleEvaluationsTopic = env("RULE_EVALUATIONS_TOPIC", "rule_evaluations"),
                shadowEvaluationsTopic = env("SHADOW_EVALUATIONS_TOPIC", "shadow_evaluations"),
                ruleChangesTopic = env("RULE_CHANGES_TOPIC", "rule_changes"),
                fraudLabelsTopic = env("FRAUD_LABELS_TOPIC", "fraud_labels"),
                dynamoEndpoint = env("DYNAMODB_ENDPOINT", "http://localhost:18000"),
                auditTable = env("DYNAMODB_DECISIONS_TABLE", "controls_decisions"),
                redisHost = env("REDIS_HOST", "localhost"),
                redisPort = env("REDIS_PORT", "6379").toInt(),
                scoringSidecarUrl = env("SCORING_SIDECAR_URL", "http://localhost:50051/score"),
                httpPort = env("HTTP_PORT", "8080").toInt(),
            )

        private fun env(
            name: String,
            default: String,
        ): String =
            System.getenv(name)?.takeIf { it.isNotBlank() } ?: default
    }
}

private class DemoSidecarScorer(
    private val endpoint: String,
) : Scorer {
    override val name: String = "demo_sidecar"
    override val version: String = "deterministic-demo-v1"
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    override suspend fun score(context: ScoringContext): ScoreResult =
        withContext(Dispatchers.IO) {
            val request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(5))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(context.event.toScoreJson()))
                .build()
            val startedAt = System.nanoTime()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            require(response.statusCode() in 200..299) {
                "demo scoring sidecar returned HTTP ${response.statusCode()}"
            }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0
            val payload = Json.parseToJsonElement(response.body()).jsonObject
            val factors = payload["shap_values"]?.jsonObject.orEmpty().map { (name, value) ->
                Factor(
                    name = name,
                    contribution = value.jsonPrimitive.doubleOrNull ?: 0.0,
                )
            }
            ScoreResult(
                score = payload.requiredDouble("calibrated_score").coerceIn(0.0, 1.0),
                rawScore = payload["raw_score"]?.jsonPrimitive?.doubleOrNull,
                contributingFactors = factors,
                modelVersion = payload["model_version"]?.jsonPrimitive?.content ?: version,
                latencyMs = elapsedMs,
            )
        }
}

private class DemoCandidateScorer : Scorer {
    override val name: String = "demo_candidate"
    override val version: String = "candidate-demo-v1"

    override suspend fun score(context: ScoringContext): ScoreResult {
        val event = context.event
        val raw = -1.5 +
            min(event.amount.amount.toDouble() / 150.0, 3.0) +
            if (event.isNewCounterparty) 1.1 else 0.0 +
            if (event.senderAccountAgeDays < 2.0) 1.0 else 0.0
        val score = (1.0 / (1.0 + kotlin.math.exp(-raw))).coerceIn(0.0, 1.0)
        return ScoreResult(
            score = score,
            rawScore = raw,
            contributingFactors = listOf(Factor("candidate_demo_score", score)),
            modelVersion = version,
            latencyMs = 1.0,
        )
    }
}

private fun com.fraudcontrols.core.TransactionEvent.toScoreJson(): String =
    buildJsonObject {
        put("event_id", eventId.value)
        put("amount", amount.amount.toDouble())
        put("sender_account_age_days", senderAccountAgeDays)
        put("is_new_counterparty", isNewCounterparty)
    }.toString()

private fun JsonObject.requiredDouble(name: String): Double =
    this[name]?.jsonPrimitive?.doubleOrNull ?: error("scoring response missing numeric $name")

private class FanOutDecisionAuditSink(
    private vararg val sinks: DecisionAuditSink,
) : DecisionAuditSink {
    override suspend fun record(record: DecisionRecord) {
        for (sink in sinks) {
            sink.record(record)
        }
    }
}

private fun demoRules(): List<RuleDefinition> =
    listOf(
        RuleDefinition(
            id = "demo-new-account-cashout",
            version = 1,
            description = "Block larger sends from very new accounts to new counterparties.",
            enabled = true,
            mode = RuleMode.ENFORCE,
            priority = 200,
            condition = RuleCondition.All(
                listOf(
                    RuleCondition.Comparison(FraudFeatureNames.AMOUNT, ComparisonOperator.GTE, RuleValue.NumberValue(100.0)),
                    RuleCondition.Comparison(
                        FraudFeatureNames.SENDER_ACCOUNT_AGE_DAYS,
                        ComparisonOperator.LTE,
                        RuleValue.NumberValue(1.0),
                    ),
                    RuleCondition.Comparison(
                        FraudFeatureNames.IS_NEW_COUNTERPARTY,
                        ComparisonOperator.EQ,
                        RuleValue.BooleanValue(true),
                    ),
                ),
            ),
            action = RuleAction(
                type = RuleActionType.BLOCK,
                reasonCode = ReasonCode("demo_new_account_cashout"),
            ),
        ),
        RuleDefinition(
            id = "demo-velocity-review",
            version = 1,
            description = "Hold senders with repeated sends in the last five minutes.",
            enabled = true,
            mode = RuleMode.ENFORCE,
            priority = 100,
            condition = RuleCondition.Comparison(
                FraudFeatureNames.SENDER_SEND_COUNT_5M,
                ComparisonOperator.GTE,
                RuleValue.NumberValue(4.0),
            ),
            action = RuleAction(
                type = RuleActionType.REVIEW_QUEUE,
                reasonCode = ReasonCode("demo_velocity_review"),
                queue = "trust_safety_l2",
            ),
        ),
        RuleDefinition(
            id = "demo-score-shadow",
            version = 1,
            description = "Shadow-only score threshold for promote/disable demos.",
            enabled = true,
            mode = RuleMode.SHADOW,
            priority = 150,
            condition = RuleCondition.Comparison(
                FraudFeatureNames.FRAUD_MODEL_SCORE,
                ComparisonOperator.GTE,
                RuleValue.NumberValue(0.55),
            ),
            action = RuleAction(
                type = RuleActionType.BLOCK,
                reasonCode = ReasonCode("demo_score_shadow"),
            ),
        ),
    )

private suspend fun retry(
    description: String,
    attempts: Int = 30,
    block: suspend () -> Unit,
) {
    var lastError: Throwable? = null
    repeat(attempts) { attempt ->
        try {
            block()
            return
        } catch (error: RuntimeException) {
            lastError = error
            println("waiting for $description (${attempt + 1}/$attempts): ${error.message}")
            delay(1_000)
        }
    }
    throw IllegalStateException("Timed out waiting for $description", lastError)
}

private fun createTopics(
    bootstrapServers: String,
    topics: List<String>,
) {
    AdminClient.create(
        Properties().apply {
            put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        },
    ).use { admin ->
        val existing = admin.listTopics().names().get()
        val missing = topics.filterNot { it in existing }
        if (missing.isNotEmpty()) {
            admin.createTopics(missing.map { NewTopic(it, 1, 1) }).all().get()
        }
    }
}

private fun dynamoClient(endpoint: String): DynamoDbClient =
    DynamoDbClient.builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.US_EAST_1)
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create("dummy", "dummy")),
        )
        .build()

private fun ensureAuditTable(
    dynamoClient: DynamoDbClient,
    tableName: String,
) {
    try {
        dynamoClient.describeTable { it.tableName(tableName) }
        return
    } catch (_: ResourceNotFoundException) {
        // Create below.
    }

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
