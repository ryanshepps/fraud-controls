package com.fraudcontrols.streaming

import com.fraudcontrols.core.DecisionAction
import com.fraudcontrols.core.EventId
import com.fraudcontrols.core.ReasonCode
import com.fraudcontrols.core.RiskBand
import com.fraudcontrols.features.FraudFeatureNames
import com.fraudcontrols.rules.NumericOperator
import com.fraudcontrols.rules.RuleCondition
import com.fraudcontrols.rules.RuleDefinition
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FraudgenDecisionProcessorTest {
    private val processor = FraudgenDecisionProcessor()

    @Test
    fun `processes fraudgen payload into a scored decision`() {
        val rule = RuleDefinition(
            id = "large-transfer-deny",
            action = DecisionAction.DENY,
            reasonCode = ReasonCode("large_transfer"),
            condition = RuleCondition.NumberCompare(
                featureName = FraudFeatureNames.AMOUNT,
                operator = NumericOperator.GREATER_THAN_OR_EQUAL,
                threshold = 1_000.0,
            ),
        )

        val result = processor.process(
            payload = validPayload(),
            rules = listOf(rule),
            decidedAt = Instant.parse("2026-01-01T12:00:05Z"),
        )

        assertEquals(EventId("evt-8"), result.decision.eventId)
        assertEquals(DecisionAction.DENY, result.decision.action)
        assertEquals(listOf(ReasonCode("large_transfer")), result.decision.reasonCodes)
        assertEquals(listOf("large-transfer-deny"), result.decision.ruleEvaluationIds)
        assertEquals(RiskBand.HIGH, result.decision.score.band)
        assertEquals(listOf("large-transfer-deny"), result.ruleEvaluation.matches.map { it.ruleId })
    }

    @Test
    fun `surfaces fraudgen parse failures`() {
        val error = assertFailsWith<FraudgenEventParseException> {
            processor.process(
                payload = "{}",
                rules = emptyList(),
                decidedAt = Instant.parse("2026-01-01T12:00:05Z"),
            )
        }

        assertContains(error.message.orEmpty(), "missing required field: event_id")
    }

    private fun validPayload(): String =
        """
        {
          "event_id": "evt-8",
          "timestamp": "2026-01-01T12:00:00+00:00",
          "sender_id": "sender-8",
          "recipient_id": "recipient-8",
          "amount": 1500.00,
          "currency": "USD",
          "type": "p2p_send",
          "sender_device_fingerprint": "device-8",
          "sender_geo": {"lat": 43.6532, "lng": -79.3832},
          "sender_balance_before": 2000.00,
          "sender_balance_after": 500.00,
          "recipient_balance_before": 50.00,
          "recipient_balance_after": 1550.00,
          "sender_account_age_days": 0.125,
          "recipient_account_age_days": 120.5,
          "is_new_counterparty": true
        }
        """.trimIndent()
}
