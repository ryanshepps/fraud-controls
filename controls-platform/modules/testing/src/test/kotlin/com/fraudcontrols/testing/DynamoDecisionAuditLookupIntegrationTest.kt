package com.fraudcontrols.testing

import com.fraudcontrols.api.GrpcDecisionService
import com.fraudcontrols.api.RuleAdminService
import com.fraudcontrols.api.installControlsAdminRoutes
import com.fraudcontrols.core.EventId
import com.fraudcontrols.core.Factor
import com.fraudcontrols.core.ScoreResult
import com.fraudcontrols.core.ScoringContext
import com.fraudcontrols.decisioning.DecisionEngine
import com.fraudcontrols.decisioning.DecisionProcessor
import com.fraudcontrols.decisioning.v1.GetDecisionRequest
import com.fraudcontrols.features.FeatureResolver
import com.fraudcontrols.features.defaultEventFeatureProviders
import com.fraudcontrols.persistence.DynamoDecisionAuditReader
import com.fraudcontrols.persistence.DynamoDecisionAuditSink
import com.fraudcontrols.scoring.Scorer
import com.fraudcontrols.scoring.ScorerFeatureProvider
import com.fraudcontrols.streaming.FraudgenEventParser
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
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
import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DynamoDecisionAuditLookupIntegrationTest {
    @Test
    fun `HTTP and gRPC decision lookup read persisted DynamoDB audit rows`() {
        val dynamodb = GenericContainer(DockerImageName.parse("amazon/dynamodb-local:2.5.4"))
            .withExposedPorts(8000)
            .waitingFor(Wait.forListeningPort())
        var dynamoClient: DynamoDbClient? = null

        dynamodb.start()
        try {
            val tableName = "decision_lookup_${UUID.randomUUID()}".replace("-", "_")
            val createdDynamoClient = dynamoClient(dynamodb)
            dynamoClient = createdDynamoClient
            createAuditTable(createdDynamoClient, tableName)

            val auditReader = DynamoDecisionAuditReader(createdDynamoClient, tableName)
            val auditSink = DynamoDecisionAuditSink(createdDynamoClient, tableName)
            val engine = DecisionEngine(
                FeatureResolver(defaultEventFeatureProviders() + ScorerFeatureProvider(FixedLookupScorer(0.42))),
            )
            val processor = DecisionProcessor(
                engine = engine,
                auditSink = auditSink,
            )
            runBlocking {
                val result = engine.decide(
                    event = FraudgenEventParser().parse(sampleFraudgenEvent("evt-ddb-lookup")),
                    rules = emptyList(),
                    decidedAt = Instant.parse("2026-05-26T12:00:00Z"),
                )
                auditSink.record(result.record)
            }

            val persisted = runBlocking { auditReader.find(EventId("evt-ddb-lookup")) }
            assertNotNull(persisted)
            assertEquals("evt-ddb-lookup", persisted.eventId)
            assertEquals("CHALLENGE", persisted.action)
            assertEquals("fixed-v1", persisted.modelVersion)

            testApplication {
                application {
                    installControlsAdminRoutes(
                        ruleAdminService = RuleAdminService(),
                        decisionRecords = auditReader,
                    )
                }

                val found = client.get("/decisions/evt-ddb-lookup")
                val missing = client.get("/decisions/missing-ddb-lookup")
                val malformed = client.get("/decisions/%20")

                assertEquals(HttpStatusCode.OK, found.status)
                assertTrue(found.bodyAsText().contains(""""event_id":"evt-ddb-lookup""""))
                assertTrue(found.bodyAsText().contains(""""action":"CHALLENGE""""))
                assertTrue(found.bodyAsText().contains(""""model_version":"fixed-v1""""))
                assertEquals(HttpStatusCode.NotFound, missing.status)
                assertEquals(HttpStatusCode.BadRequest, malformed.status)
            }

            val grpcService = GrpcDecisionService(
                processor = processor,
                ruleSource = { emptyList() },
                decisionRecords = auditReader,
            )
            val grpcRecord = runBlocking {
                grpcService.getDecision(
                    GetDecisionRequest.newBuilder()
                        .setEventId("evt-ddb-lookup")
                        .build(),
                )
            }
            assertEquals(persisted.schemaVersion, grpcRecord.schemaVersion)
            assertEquals(persisted.eventId, grpcRecord.eventId)
            assertEquals(persisted.reasonCodes, grpcRecord.reasonCodesList)
            assertEquals(persisted.score, grpcRecord.score)
            assertEquals(persisted.modelVersion, grpcRecord.modelVersion)
            assertEquals(persisted.featuresJson, grpcRecord.featuresJson)

            val missingGrpc = assertFailsWith<StatusRuntimeException> {
                runBlocking {
                    grpcService.getDecision(GetDecisionRequest.newBuilder().setEventId("missing-ddb-lookup").build())
                }
            }
            val malformedGrpc = assertFailsWith<StatusRuntimeException> {
                runBlocking {
                    grpcService.getDecision(GetDecisionRequest.newBuilder().setEventId(" ").build())
                }
            }
            assertEquals(Status.Code.NOT_FOUND, missingGrpc.status.code)
            assertEquals(Status.Code.INVALID_ARGUMENT, malformedGrpc.status.code)
        } finally {
            dynamoClient?.close()
            dynamodb.stop()
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
}

private fun sampleFraudgenEvent(eventId: String): String =
    """
    {
      "event_id": "$eventId",
      "timestamp": "2026-05-26T12:00:00Z",
      "sender_id": "sender-1",
      "recipient_id": "recipient-1",
      "amount": "2500.00",
      "currency": "USD",
      "type": "p2p_send",
      "sender_device_fingerprint": "device-1",
      "sender_geo": {"lat": 43.6532, "lng": -79.3832},
      "sender_balance_before": "3000.00",
      "sender_balance_after": "500.00",
      "recipient_balance_before": "50.00",
      "recipient_balance_after": "2550.00",
      "sender_account_age_days": 30.0,
      "recipient_account_age_days": 120.0,
      "is_new_counterparty": true
    }
    """.trimIndent()

private class FixedLookupScorer(
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
