package com.fraudcontrols.decisioning

import com.fraudcontrols.core.CustomerId
import com.fraudcontrols.core.DecisionAction
import com.fraudcontrols.core.DeviceFingerprint
import com.fraudcontrols.core.EventId
import com.fraudcontrols.core.GeoPoint
import com.fraudcontrols.core.Money
import com.fraudcontrols.core.ReasonCode
import com.fraudcontrols.core.RiskBand
import com.fraudcontrols.core.ScoreResult
import com.fraudcontrols.core.TransactionEvent
import com.fraudcontrols.core.TransactionType
import com.fraudcontrols.features.FraudFeatureNames
import com.fraudcontrols.rules.NumericOperator
import com.fraudcontrols.rules.RuleCondition
import com.fraudcontrols.rules.RuleDefinition
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DecisionEngineTest {
    private val engine = DecisionEngine()
    private val decidedAt = Instant.parse("2026-01-02T00:00:00Z")

    @Test
    fun `falls back to score band when no rules match`() {
        val result = engine.decide(
            event = sampleEvent(amount = "25.00"),
            rules = listOf(largeAmountRule()),
            score = sampleScore(RiskBand.MEDIUM),
            decidedAt = decidedAt,
        )

        assertEquals(DecisionAction.CHALLENGE, result.decision.action)
        assertEquals(listOf(ReasonCode("score_medium")), result.decision.reasonCodes)
        assertEquals(emptyList(), result.decision.ruleEvaluationIds)
        assertEquals(emptyList(), result.ruleEvaluation.matches)
        assertEquals(EventId("evt-1"), result.features.eventId)
    }

    @Test
    fun `uses highest severity matched rule action over score band`() {
        val result = engine.decide(
            event = sampleEvent(amount = "1500.00"),
            rules = listOf(
                largeAmountRule(
                    id = "challenge-large",
                    action = DecisionAction.CHALLENGE,
                    reasonCode = "large_amount",
                ),
                largeAmountRule(
                    id = "deny-large",
                    action = DecisionAction.DENY,
                    reasonCode = "deny_large_amount",
                ),
            ),
            score = sampleScore(RiskBand.LOW),
            decidedAt = decidedAt,
        )

        assertEquals(DecisionAction.DENY, result.decision.action)
        assertEquals(
            listOf(ReasonCode("large_amount"), ReasonCode("deny_large_amount")),
            result.decision.reasonCodes,
        )
        assertEquals(listOf("challenge-large", "deny-large"), result.decision.ruleEvaluationIds)
        assertEquals(decidedAt, result.decision.decidedAt)
    }

    @Test
    fun `carries skipped rule diagnostics without changing the decision`() {
        val result = engine.decide(
            event = sampleEvent(amount = "1500.00"),
            rules = listOf(
                RuleDefinition(
                    id = "missing-feature",
                    action = DecisionAction.DENY,
                    reasonCode = ReasonCode("missing_feature_rule"),
                    condition = RuleCondition.TextEquals("unknown_feature", "value"),
                ),
            ),
            score = sampleScore(RiskBand.HIGH),
            decidedAt = decidedAt,
        )

        assertEquals(DecisionAction.HOLD, result.decision.action)
        assertEquals(listOf(ReasonCode("score_high")), result.decision.reasonCodes)
        assertEquals(emptyList(), result.decision.ruleEvaluationIds)
        assertEquals("missing-feature", result.ruleEvaluation.skipped.single().ruleId)
        assertTrue(result.ruleEvaluation.skipped.single().reason.contains("missing text feature"))
    }

    @Test
    fun `scores transaction before applying score-band fallback`() {
        val result = engine.decide(
            event = sampleEvent(amount = "6000.00"),
            rules = emptyList(),
            decidedAt = decidedAt,
        )

        assertEquals(DecisionAction.HOLD, result.decision.action)
        assertEquals(RiskBand.HIGH, result.decision.score.band)
        assertEquals(listOf(ReasonCode("score_high")), result.decision.reasonCodes)
        assertTrue(result.decision.score.factors.isNotEmpty())
    }

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
        action: DecisionAction = DecisionAction.HOLD,
        reasonCode: String = "large_amount",
    ): RuleDefinition =
        RuleDefinition(
            id = id,
            action = action,
            reasonCode = ReasonCode(reasonCode),
            condition = RuleCondition.NumberCompare(
                featureName = FraudFeatureNames.AMOUNT,
                operator = NumericOperator.GREATER_THAN_OR_EQUAL,
                threshold = 1000.0,
            ),
        )

    private fun sampleScore(band: RiskBand): ScoreResult =
        ScoreResult(
            score = when (band) {
                RiskBand.LOW -> 0.1
                RiskBand.MEDIUM -> 0.5
                RiskBand.HIGH -> 0.9
            },
            band = band,
            factors = emptyList(),
            latencyMs = 3,
        )
}
