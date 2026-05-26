package com.fraudcontrols.demo

import com.fraudcontrols.api.GlobalKillSwitchService
import com.fraudcontrols.api.InMemoryDecisionRecordStore
import com.fraudcontrols.api.KafkaRuleChangeAuditPublisher
import com.fraudcontrols.api.RuleAdminService
import com.fraudcontrols.api.startAdminHttpServer
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
import com.fraudcontrols.scoring.ScorerFactory
import com.fraudcontrols.scoring.ScorerFeatureProvider
import com.fraudcontrols.scoring.XGBoostScoreClient
import com.fraudcontrols.scoring.XGBoostScoreResponse
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
        val runtime = RuntimeConfigLoader().load()
        val config = runtime.application
        println("starting ${config.serviceName} ${config.environment} runtime with kafka=${config.kafkaBootstrapServers}")

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
            initialRules = runtime.initialRules,
            auditPublisher = KafkaRuleChangeAuditPublisher(producer, config.ruleChangesTopic),
        )

        val baseFeatureProviders = defaultEventFeatureProviders() + defaultVelocityFeatureProviders(velocityStore)
        val scorerFactory = ScorerFactory(
            featureResolver = FeatureResolver(baseFeatureProviders),
            ruleBasedConfigsByPath = runtime.ruleBasedConfigsByPath,
            xgBoostClientFactory = { definition ->
                HttpXGBoostScoreClient(
                    endpoint = definition.sidecarAddress
                        ?: throw RuntimeConfigException("xgboost scorer ${definition.name} requires sidecar_address"),
                )
            },
            shadowEvaluationSink = KafkaShadowEvaluationSink(producer, config.shadowEvaluationsTopic),
        )
        val scorers = scorerFactory.build(runtime.scoring)
        val scoringFeatureProviders = runtime.scoring.features.map { binding ->
            if (binding.name != FraudFeatureNames.FRAUD_MODEL_SCORE) {
                throw RuntimeConfigException("unsupported scoring feature binding: ${binding.name}")
            }
            ScorerFeatureProvider(scorers.getValue(binding.scorer))
        }
        val processor = DecisionProcessor(
            engine = DecisionEngine(
                featureResolver = FeatureResolver(baseFeatureProviders + scoringFeatureProviders),
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
            globalKillSwitchService = GlobalKillSwitchService(
                auditPublisher = KafkaRuleChangeAuditPublisher(producer, config.ruleChangesTopic),
            ),
            host = config.httpHost,
            port = config.httpPort,
            metricsPath = config.metricsPath,
            metricsScrape = controlsMetrics::scrape,
        )
        println("admin API ready on ${config.httpHost}:${config.httpPort}; metrics at ${config.metricsPath}")

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

private class HttpXGBoostScoreClient(
    private val endpoint: String,
) : XGBoostScoreClient {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    override suspend fun score(
        context: ScoringContext,
        modelId: String,
    ): XGBoostScoreResponse =
        withContext(Dispatchers.IO) {
            val request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(5))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(context.event.toScoreJson(modelId)))
                .build()
            val startedAt = System.nanoTime()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            require(response.statusCode() in 200..299) {
                "demo scoring sidecar returned HTTP ${response.statusCode()}"
            }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0
            val payload = Json.parseToJsonElement(response.body()).jsonObject
            XGBoostScoreResponse(
                rawScore = payload.requiredDouble("raw_score"),
                shapValues = payload["shap_values"]?.jsonObject.orEmpty().mapValues { (_, value) ->
                    value.jsonPrimitive.doubleOrNull ?: 0.0
                } + mapOf("sidecar_latency_ms" to elapsedMs),
            )
        }
}

private fun com.fraudcontrols.core.TransactionEvent.toScoreJson(modelId: String): String =
    buildJsonObject {
        put("model_id", modelId)
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
