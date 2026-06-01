package com.fraudcontrols.api

import com.fraudcontrols.core.Factor
import com.fraudcontrols.core.ScoreResult
import com.fraudcontrols.core.ScoringContext
import com.fraudcontrols.decisioning.DecisionEngine
import com.fraudcontrols.decisioning.DecisionProcessor
import com.fraudcontrols.features.FeatureResolver
import com.fraudcontrols.features.defaultEventFeatureProviders
import com.fraudcontrols.scoring.Scorer
import com.fraudcontrols.scoring.ScorerFeatureProvider
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.serialization.StringSerializer
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminHttpApiTest {
    private val clock = Clock.fixed(Instant.parse("2026-05-26T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `rule lifecycle routes version and audit immutable changes`() = testApplication {
        val audit = InMemoryRuleChangeAuditPublisher()
        val rules = RuleAdminService(auditPublisher = audit, clock = clock)
        application {
            installControlsAdminRoutes(
                ruleAdminService = rules,
                decisionRecords = InMemoryDecisionRecordStore(),
            )
        }

        val create =
            client.post("/rules") {
                contentType(ContentType.Application.Json)
                setBody(ruleJson(id = "shadow-score", mode = "shadow", actor = "risk-admin"))
            }
        val update =
            client.put("/rules/shadow-score") {
                contentType(ContentType.Application.Json)
                setBody(ruleJson(id = "ignored", mode = "shadow", actor = "risk-admin", priority = 200))
            }
        val promote =
            client.post("/rules/shadow-score/promote") {
                contentType(ContentType.Application.Json)
                setBody("""{"actor":"risk-admin","confirm":true}""")
            }
        val disable =
            client.post("/rules/shadow-score/disable") {
                contentType(ContentType.Application.Json)
                setBody("""{"actor":"risk-admin"}""")
            }
        val history = client.get("/rules/shadow-score/history")
        val list = client.get("/rules")

        assertEquals(HttpStatusCode.Created, create.status)
        assertEquals(HttpStatusCode.OK, update.status)
        assertEquals(HttpStatusCode.OK, promote.status)
        assertEquals(HttpStatusCode.OK, disable.status)
        assertEquals(HttpStatusCode.OK, history.status)
        assertTrue(history.bodyAsText().contains(""""version":1"""))
        assertTrue(history.bodyAsText().contains(""""version":2"""))
        assertTrue(history.bodyAsText().contains(""""version":3"""))
        assertTrue(history.bodyAsText().contains(""""version":4"""))
        assertTrue(history.bodyAsText().contains(""""priority":200"""))
        assertTrue(list.bodyAsText().contains(""""mode":"disabled""""))
        assertEquals(
            listOf(RuleChangeType.CREATE, RuleChangeType.UPDATE, RuleChangeType.PROMOTE, RuleChangeType.DISABLE),
            audit.events().map { it.changeType },
        )
        assertTrue(audit.events().all { it.actor == "risk-admin" })
        assertTrue(audit.events().all { it.occurredAt == Instant.parse("2026-05-26T12:00:00Z") })
        assertTrue(
            audit
                .events()
                .last()
                .diff
                .containsKey("enabled"),
        )
    }

    @Test
    fun `optional action bodies default the actor while preserving validation`() = testApplication {
        val audit = InMemoryRuleChangeAuditPublisher()
        val rules = RuleAdminService(auditPublisher = audit, clock = clock)
        application {
            installControlsAdminRoutes(
                ruleAdminService = rules,
                decisionRecords = InMemoryDecisionRecordStore(),
            )
        }

        client.post("/rules") {
            contentType(ContentType.Application.Json)
            setBody(ruleJson(id = "disable-without-body", mode = "shadow", actor = "risk-admin"))
        }
        client.post("/rules") {
            contentType(ContentType.Application.Json)
            setBody(ruleJson(id = "promote-without-confirmation", mode = "shadow", actor = "risk-admin"))
        }

        val disable = client.post("/rules/disable-without-body/disable")
        val promote = client.post("/rules/promote-without-confirmation/promote")

        assertEquals(HttpStatusCode.OK, disable.status)
        assertTrue(disable.bodyAsText().contains(""""mode":"disabled""""))
        assertEquals(HttpStatusCode.BadRequest, promote.status)
        assertTrue(promote.bodyAsText().contains(""""error":"promotion requires confirmation""""))
        assertEquals("local", audit.events().last().actor)
    }

    @Test
    fun `admin routes reject malformed rule payloads and missing decisions`() = testApplication {
        application {
            installControlsAdminRoutes(
                ruleAdminService = RuleAdminService(),
                decisionRecords = InMemoryDecisionRecordStore(),
            )
        }

        val badRule =
            client.post("/rules") {
                contentType(ContentType.Application.Json)
                setBody("""{"id":"bad"}""")
            }
        val missingDecision = client.get("/decisions/missing")

        assertEquals(HttpStatusCode.BadRequest, badRule.status)
        assertEquals(HttpStatusCode.NotFound, missingDecision.status)
        assertTrue(badRule.bodyAsText().contains(""""error":"""))
        assertTrue(badRule.headers[HttpHeaders.ContentType].orEmpty().contains(ContentType.Application.Json.contentType))
    }

    @Test
    fun `status pages return JSON errors for malformed JSON and rule conflicts`() = testApplication {
        val rules = RuleAdminService()
        application {
            installControlsAdminRoutes(
                ruleAdminService = rules,
                decisionRecords = InMemoryDecisionRecordStore(),
            )
        }

        val malformed =
            client.post("/rules") {
                contentType(ContentType.Application.Json)
                setBody("""{"id":""")
            }
        val created =
            client.post("/rules") {
                contentType(ContentType.Application.Json)
                setBody(ruleJson(id = "duplicate-score", mode = "shadow", actor = "risk-admin"))
            }
        val duplicate =
            client.post("/rules") {
                contentType(ContentType.Application.Json)
                setBody(ruleJson(id = "duplicate-score", mode = "shadow", actor = "risk-admin"))
            }
        val createdEnforce =
            client.post("/rules") {
                contentType(ContentType.Application.Json)
                setBody(ruleJson(id = "enforce-score", mode = "enforce", actor = "risk-admin"))
            }
        val promoteEnforce =
            client.post("/rules/enforce-score/promote") {
                contentType(ContentType.Application.Json)
                setBody("""{"actor":"risk-admin","confirm":true}""")
            }

        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        assertTrue(malformed.bodyAsText().contains(""""error":"""))
        assertTrue(malformed.headers[HttpHeaders.ContentType].orEmpty().contains(ContentType.Application.Json.contentType))
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals(HttpStatusCode.Conflict, duplicate.status)
        assertTrue(duplicate.bodyAsText().contains(""""error":"rule already exists: duplicate-score""""))
        assertEquals(HttpStatusCode.Created, createdEnforce.status)
        assertEquals(HttpStatusCode.BadRequest, promoteEnforce.status)
        assertTrue(promoteEnforce.bodyAsText().contains(""""error":"only shadow rules can be promoted""""))
    }

    @Test
    fun `required rule bodies return JSON unsupported media errors`() = testApplication {
        application {
            installControlsAdminRoutes(
                ruleAdminService = RuleAdminService(),
                decisionRecords = InMemoryDecisionRecordStore(),
            )
        }

        val response =
            client.post("/rules") {
                setBody(ruleJson(id = "missing-content-type", mode = "shadow", actor = "risk-admin"))
            }

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        assertTrue(response.bodyAsText().contains(""""error":"content type must be application/json""""))
        assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().contains(ContentType.Application.Json.contentType))
    }

    @Test
    fun `decision lookup returns the persisted audit record`() = testApplication {
        val store = InMemoryDecisionRecordStore()
        runBlocking {
            val result = DecisionProcessor(
                engine =
                DecisionEngine(
                    FeatureResolver(defaultEventFeatureProviders() + ScorerFeatureProvider(AdminFixedScorer(0.42))),
                ),
                auditSink = store,
            ).process(
                event =
                com.fraudcontrols.streaming
                    .FraudgenEventParser()
                    .parse(sampleFraudgenEvent("evt-http-1")),
                rules = emptyList(),
                decidedAt = Instant.parse("2026-05-26T12:00:00Z"),
            )
            store.record(result.record)
        }
        application {
            installControlsAdminRoutes(
                ruleAdminService = RuleAdminService(),
                decisionRecords = store,
            )
        }

        val response = client.get("/decisions/evt-http-1")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains(""""event_id":"evt-http-1""""))
        assertTrue(response.bodyAsText().contains(""""action":"CHALLENGE""""))
        assertTrue(response.bodyAsText().contains(""""model_version":"fixed-v1""""))
    }

    @Test
    fun `admin API does not expose removed global emergency endpoint`() = testApplication {
        application {
            installControlsAdminRoutes(
                ruleAdminService = RuleAdminService(),
                decisionRecords = InMemoryDecisionRecordStore(),
            )
        }

        val response =
            client.post("/admin/global-kill") {
                contentType(ContentType.Application.Json)
                setBody("""{"actor":"ops"}""")
            }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `metrics route exposes prometheus scrape when configured`() = testApplication {
        application {
            installControlsAdminRoutes(
                ruleAdminService = RuleAdminService(),
                decisionRecords = InMemoryDecisionRecordStore(),
                metricsScrape = { "controls_decisions_total 1.0\n" },
            )
        }

        val response = client.get("/metrics")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("controls_decisions_total 1.0\n", response.bodyAsText())
    }

    @Test
    fun `kafka rule change publisher writes audit events to rule changes topic`() = runTest {
        val producer = MockProducer<String, String>(true, StringSerializer(), StringSerializer())
        val publisher = KafkaRuleChangeAuditPublisher(producer)

        publisher.publish(
            RuleChangeEvent(
                ruleId = "shadow-score",
                ruleVersion = 2,
                changeType = RuleChangeType.UPDATE,
                actor = "risk-admin",
                occurredAt = Instant.parse("2026-05-26T12:00:00Z"),
                diff = mapOf("priority" to "100->200"),
            ),
        )

        val record = producer.history().single()
        assertEquals("rule_changes", record.topic())
        assertEquals("shadow-score", record.key())
        assertTrue(record.value().contains(""""change_type":"update""""))
        assertTrue(record.value().contains(""""actor":"risk-admin""""))
        assertTrue(record.value().contains(""""priority":"100->200""""))
    }

    private fun ruleJson(
        id: String,
        mode: String,
        actor: String,
        priority: Int = 100,
    ): String =
        """
        {
          "id": "$id",
          "actor": "$actor",
          "mode": "$mode",
          "priority": $priority,
          "when": {
            "feature": "fraud_model_score",
            "op": "gte",
            "value": 0.8
          },
          "action": {
            "type": "block",
            "reason_code": "model_score_high"
          }
        }
        """.trimIndent()
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

private class AdminFixedScorer(
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
