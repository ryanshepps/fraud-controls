package com.fraudcontrols.api

import com.fraudcontrols.core.Factor
import com.fraudcontrols.core.ReasonCode
import com.fraudcontrols.core.ScoreResult
import com.fraudcontrols.core.ScoringContext
import com.fraudcontrols.decisioning.DecisionEngine
import com.fraudcontrols.decisioning.DecisionProcessor
import com.fraudcontrols.decisioning.v1.DecisionAction
import com.fraudcontrols.decisioning.v1.EvaluateRequest
import com.fraudcontrols.decisioning.v1.GetDecisionRequest
import com.fraudcontrols.features.FeatureResolver
import com.fraudcontrols.features.FraudFeatureNames
import com.fraudcontrols.features.defaultEventFeatureProviders
import com.fraudcontrols.rules.ComparisonOperator
import com.fraudcontrols.rules.RuleAction
import com.fraudcontrols.rules.RuleActionType
import com.fraudcontrols.rules.RuleCondition
import com.fraudcontrols.rules.RuleDefinition
import com.fraudcontrols.rules.RuleValue
import com.fraudcontrols.scoring.Scorer
import com.fraudcontrols.scoring.ScorerFeatureProvider
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GrpcDecisionServiceTest {
    @Test
    fun `evaluate returns decision and get decision returns matching audit record`() = runTest {
        val store = InMemoryDecisionRecordStore()
        val service = decisionService(store = store)

        val response =
            service.evaluate(
                EvaluateRequest
                    .newBuilder()
                    .setTransactionJson(sampleFraudgenEvent())
                    .build(),
            )
        val record = service.getDecision(GetDecisionRequest.newBuilder().setEventId("evt-api-1").build())

        assertEquals("evt-api-1", response.eventId)
        assertEquals(DecisionAction.DECISION_ACTION_DENY, response.action)
        assertEquals(listOf("model_score_high"), response.reasonCodesList)
        assertEquals(0.92, response.score)
        assertEquals("2026-05-26T12:00:00Z", response.decidedAt)
        assertEquals(response.eventId, record.eventId)
        assertEquals(response.action, record.action)
        assertEquals(response.reasonCodesList, record.reasonCodesList)
        assertEquals(response.score, record.score)
        assertEquals("fixed-v1", record.modelVersion)
        assertTrue(record.featuresJson.contains(FraudFeatureNames.FRAUD_MODEL_SCORE))
    }

    @Test
    fun `get decision maps missing records to not found`() = runTest {
        val error =
            assertFailsWith<StatusRuntimeException> {
                decisionService().getDecision(GetDecisionRequest.newBuilder().setEventId("missing").build())
            }

        assertEquals(Status.Code.NOT_FOUND, error.status.code)
    }

    @Test
    fun `evaluate maps malformed payloads to invalid argument`() = runTest {
        val error =
            assertFailsWith<StatusRuntimeException> {
                decisionService().evaluate(
                    EvaluateRequest
                        .newBuilder()
                        .setTransactionJson("""{"event_id":"evt-bad"}""")
                        .build(),
                )
            }

        assertEquals(Status.Code.INVALID_ARGUMENT, error.status.code)
    }

    private fun decisionService(store: InMemoryDecisionRecordStore = InMemoryDecisionRecordStore()): GrpcDecisionService = GrpcDecisionService(
        processor =
        DecisionProcessor(
            engine =
            DecisionEngine(
                FeatureResolver(defaultEventFeatureProviders() + ScorerFeatureProvider(GrpcFixedScorer(0.92))),
            ),
            auditSink = store,
        ),
        ruleSource = { listOf(modelScoreRule()) },
        decisionRecords = store,
        clock = Clock.fixed(Instant.parse("2026-05-26T12:00:00Z"), ZoneOffset.UTC),
    )
}

private fun modelScoreRule(): RuleDefinition = RuleDefinition(
    id = "model-score-high",
    version = 1,
    condition =
    RuleCondition.Comparison(
        featureName = FraudFeatureNames.FRAUD_MODEL_SCORE,
        operator = ComparisonOperator.GTE,
        value = RuleValue.NumberValue(0.8),
    ),
    action =
    RuleAction(
        type = RuleActionType.BLOCK,
        reasonCode = ReasonCode("model_score_high"),
    ),
)

private fun sampleFraudgenEvent(eventId: String = "evt-api-1"): String =
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

private class GrpcFixedScorer(
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
