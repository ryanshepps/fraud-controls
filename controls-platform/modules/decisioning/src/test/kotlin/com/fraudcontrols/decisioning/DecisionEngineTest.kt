package com.fraudcontrols.decisioning

import com.fraudcontrols.core.CustomerId
import com.fraudcontrols.core.Decision
import com.fraudcontrols.core.DecisionAction
import com.fraudcontrols.core.DeviceFingerprint
import com.fraudcontrols.core.EventId
import com.fraudcontrols.core.Factor
import com.fraudcontrols.core.FeatureSnapshot
import com.fraudcontrols.core.GeoPoint
import com.fraudcontrols.core.Money
import com.fraudcontrols.core.ReasonCode
import com.fraudcontrols.core.ScoreResult
import com.fraudcontrols.core.ScoringContext
import com.fraudcontrols.core.TransactionEvent
import com.fraudcontrols.core.TransactionType
import com.fraudcontrols.features.FeatureResolver
import com.fraudcontrols.features.FraudFeatureNames
import com.fraudcontrols.features.defaultEventFeatureProviders
import com.fraudcontrols.rules.ComparisonOperator
import com.fraudcontrols.rules.RuleAction
import com.fraudcontrols.rules.RuleActionType
import com.fraudcontrols.rules.RuleCondition
import com.fraudcontrols.rules.RuleDefinition
import com.fraudcontrols.rules.RuleEvaluationResult
import com.fraudcontrols.rules.RuleMode
import com.fraudcontrols.rules.RuleValue
import com.fraudcontrols.scoring.Scorer
import com.fraudcontrols.scoring.ScorerFeatureProvider
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DecisionEngineTest {
    private val decidedAt = Instant.parse("2026-01-02T00:00:00Z")

    @Test
    fun `falls back to score threshold when no enforce rule resolves an action`() = runTest {
        val result = engine(score = sampleScore(0.5)).decide(
            event = sampleEvent(amount = "25.00"),
            rules = listOf(largeAmountRule()),
            decidedAt = decidedAt,
        )

        assertEquals(DecisionAction.CHALLENGE, result.decision.action)
        assertEquals(listOf(ReasonCode("score_medium")), result.decision.reasonCodes)
        assertEquals(emptyList(), result.decision.ruleEvaluationIds)
        assertEquals(emptyList(), result.ruleEvaluation.matches)
        assertEquals(EventId("evt-1"), result.features.eventId)
        assertTrue(result.features.values.containsKey(FraudFeatureNames.FRAUD_MODEL_SCORE))
        assertSame(result.decision, result.record.decision)
        assertSame(result.features, result.record.features)
        assertSame(result.ruleEvaluation, result.record.ruleEvaluation)
    }

    @Test
    fun `uses highest priority enforce rule over score threshold and shadow matches`() = runTest {
        val result = engine(score = sampleScore(0.1)).decide(
            event = sampleEvent(amount = "1500.00"),
            rules = listOf(
                largeAmountRule(
                    id = "shadow-large",
                    mode = RuleMode.SHADOW,
                    priority = 500,
                    action = RuleActionType.BLOCK,
                    reasonCode = "shadow_large_amount",
                ),
                largeAmountRule(
                    id = "challenge-large",
                    mode = RuleMode.ENFORCE,
                    priority = 100,
                    action = RuleActionType.CHALLENGE,
                    reasonCode = "large_amount",
                ),
                largeAmountRule(
                    id = "deny-large",
                    mode = RuleMode.ENFORCE,
                    priority = 200,
                    action = RuleActionType.BLOCK,
                    reasonCode = "deny_large_amount",
                ),
            ),
            decidedAt = decidedAt,
        )

        assertEquals(DecisionAction.DENY, result.decision.action)
        assertEquals(listOf(ReasonCode("deny_large_amount")), result.decision.reasonCodes)
        assertEquals(listOf("shadow-large", "challenge-large", "deny-large"), result.decision.ruleEvaluationIds)
        assertEquals("deny-large", result.ruleEvaluation.resolvedAction?.ruleId)
        assertEquals(decidedAt, result.decision.decidedAt)
    }

    @Test
    fun `carries skipped rule diagnostics without changing the decision`() = runTest {
        val result = engine(score = sampleScore(0.9)).decide(
            event = sampleEvent(amount = "1500.00"),
            rules = listOf(
                RuleDefinition(
                    id = "missing-feature",
                    version = 1,
                    condition = RuleCondition.Comparison(
                        featureName = "unknown_feature",
                        operator = ComparisonOperator.EQ,
                        value = RuleValue.TextValue("value"),
                    ),
                    action = RuleAction(
                        type = RuleActionType.BLOCK,
                        reasonCode = ReasonCode("missing_feature_rule"),
                    ),
                ),
            ),
            decidedAt = decidedAt,
        )

        assertEquals(DecisionAction.HOLD, result.decision.action)
        assertEquals(listOf(ReasonCode("score_high")), result.decision.reasonCodes)
        assertEquals(emptyList(), result.decision.ruleEvaluationIds)
        assertEquals("missing-feature", result.ruleEvaluation.skipped.single().ruleId)
        assertTrue(result.ruleEvaluation.skipped.single().reason.contains("unknown_feature"))
        assertTrue(result.ruleEvaluation.skipped.single().reason.contains("unavailable"))
    }

    @Test
    fun `uses scored model output as a rule feature`() = runTest {
        val result = engine(score = sampleScore(0.8)).decide(
            event = sampleEvent(amount = "25.00"),
            rules = listOf(
                RuleDefinition(
                    id = "model-score-high",
                    version = 1,
                    condition = RuleCondition.Comparison(
                        featureName = FraudFeatureNames.FRAUD_MODEL_SCORE,
                        operator = ComparisonOperator.GTE,
                        value = RuleValue.NumberValue(0.7),
                    ),
                    action = RuleAction(
                        type = RuleActionType.CHALLENGE,
                        reasonCode = ReasonCode("model_score_high"),
                    ),
                ),
            ),
            decidedAt = decidedAt,
        )

        assertEquals(DecisionAction.CHALLENGE, result.decision.action)
        assertEquals(listOf(ReasonCode("model_score_high")), result.decision.reasonCodes)
        assertEquals(listOf("model-score-high"), result.decision.ruleEvaluationIds)
        assertEquals(0.8, result.decision.score.score)
    }

    @Test
    fun `uses resolved rule id when matching rule has no reason code`() = runTest {
        val result = engine(score = sampleScore(0.1)).decide(
            event = sampleEvent(amount = "1500.00"),
            rules = listOf(largeAmountRule(reasonCode = null)),
            decidedAt = decidedAt,
        )

        assertEquals(DecisionAction.HOLD, result.decision.action)
        assertEquals(listOf(ReasonCode("rule_large-amount")), result.decision.reasonCodes)
    }

    @Test
    fun `processor records audit and publishes evaluation then decision`() = runTest {
        val auditSink = RecordingAuditSink()
        val decisionPublisher = RecordingDecisionPublisher()
        val ruleEvaluationPublisher = RecordingRuleEvaluationPublisher()
        val processor = DecisionProcessor(
            engine = engine(score = sampleScore(0.1)),
            auditSink = auditSink,
            decisionPublisher = decisionPublisher,
            ruleEvaluationPublisher = ruleEvaluationPublisher,
        )

        val result = processor.process(
            event = sampleEvent(amount = "1500.00"),
            rules = listOf(largeAmountRule(action = RuleActionType.BLOCK, reasonCode = "large_amount")),
            decidedAt = decidedAt,
        )

        assertEquals(listOf(result.record), auditSink.records)
        assertEquals(listOf(result.ruleEvaluation), ruleEvaluationPublisher.evaluations)
        assertEquals(listOf(result.decision), decisionPublisher.decisions)
        assertEquals(0.1, auditSink.records.single().score.score)
        assertTrue(auditSink.records.single().features.values.containsKey(FraudFeatureNames.AMOUNT))
    }

    @Test
    fun `requires fraud model score from scorer feature provider`() = runTest {
        val engine = DecisionEngine(featureResolver = FeatureResolver(defaultEventFeatureProviders()))

        val error = assertFailsWith<IllegalStateException> {
            engine.decide(
                event = sampleEvent(amount = "25.00"),
                rules = emptyList(),
                decidedAt = decidedAt,
            )
        }

        assertTrue(error.message.orEmpty().contains("fraud_model_score unavailable"))
        assertTrue(error.message.orEmpty().contains("no provider registered"))
    }

    private fun engine(score: ScoreResult): DecisionEngine =
        DecisionEngine(
            featureResolver = FeatureResolver(defaultEventFeatureProviders() + ScorerFeatureProvider(FixedScorer(score))),
        )

    private fun sampleEvent(amount: String): TransactionEvent =
        TransactionEvent(
            eventId = EventId("evt-1"),
            timestamp = Instant.parse("2026-01-01T00:00:00Z"),
            senderId = CustomerId("sender-1"),
            recipientId = CustomerId("recipient-1"),
            amount = Money.usd(amount),
            transactionType = TransactionType.P2P_SEND,
            senderDeviceFingerprint = DeviceFingerprint("device-1"),
            senderGeo = GeoPoint(latitude = 43.6532, longitude = -79.3832),
            senderBalanceBefore = BigDecimal("2000.00"),
            senderBalanceAfter = BigDecimal("500.00"),
            recipientBalanceBefore = BigDecimal("50.00"),
            recipientBalanceAfter = BigDecimal("1550.00"),
            senderAccountAgeDays = 30.0,
            recipientAccountAgeDays = 120.0,
            isNewCounterparty = true,
        )

    private fun largeAmountRule(
        id: String = "large-amount",
        mode: RuleMode = RuleMode.ENFORCE,
        priority: Int = 100,
        action: RuleActionType = RuleActionType.REVIEW_QUEUE,
        reasonCode: String? = "large_amount",
    ): RuleDefinition =
        RuleDefinition(
            id = id,
            version = 1,
            mode = mode,
            priority = priority,
            condition = RuleCondition.Comparison(
                featureName = FraudFeatureNames.AMOUNT,
                operator = ComparisonOperator.GTE,
                value = RuleValue.NumberValue(1000.0),
            ),
            action = RuleAction(
                type = action,
                reasonCode = reasonCode?.let(::ReasonCode),
                queue = if (action == RuleActionType.REVIEW_QUEUE) "trust_safety_l2" else null,
            ),
        )

    private fun sampleScore(score: Double): ScoreResult =
        ScoreResult(
            score = score,
            rawScore = null,
            contributingFactors = listOf(Factor(name = "test_score", contribution = 0.1)),
            modelVersion = "fixed-v1",
            latencyMs = 3.0,
        )
}

private class FixedScorer(
    private val result: ScoreResult,
) : Scorer {
    override val name: String = "fixed"
    override val version: String = result.modelVersion

    override suspend fun score(context: ScoringContext): ScoreResult = result
}

private class RecordingAuditSink : DecisionAuditSink {
    val records = mutableListOf<DecisionRecord>()

    override suspend fun record(record: DecisionRecord) {
        records += record
    }
}

private class RecordingDecisionPublisher : DecisionPublisher {
    val decisions = mutableListOf<Decision>()

    override suspend fun publish(decision: Decision) {
        decisions += decision
    }
}

private class RecordingRuleEvaluationPublisher : RuleEvaluationPublisher {
    val evaluations = mutableListOf<RuleEvaluationResult>()

    override suspend fun publish(evaluation: RuleEvaluationResult) {
        evaluations += evaluation
    }
}
