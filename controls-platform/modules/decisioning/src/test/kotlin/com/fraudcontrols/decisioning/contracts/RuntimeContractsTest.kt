package com.fraudcontrols.decisioning.contracts

import com.fraudcontrols.core.CustomerId
import com.fraudcontrols.core.Decision
import com.fraudcontrols.core.DecisionAction
import com.fraudcontrols.core.DeviceFingerprint
import com.fraudcontrols.core.EventId
import com.fraudcontrols.core.Factor
import com.fraudcontrols.core.FeatureSnapshot
import com.fraudcontrols.core.FeatureValue
import com.fraudcontrols.core.GeoPoint
import com.fraudcontrols.core.Money
import com.fraudcontrols.core.ReasonCode
import com.fraudcontrols.core.ScoreResult
import com.fraudcontrols.core.TransactionEvent
import com.fraudcontrols.core.TransactionType
import com.fraudcontrols.decisioning.DecisionRecord
import com.fraudcontrols.features.FraudFeatureNames
import com.fraudcontrols.rules.ResolvedRuleAction
import com.fraudcontrols.rules.RuleAction
import com.fraudcontrols.rules.RuleActionType
import com.fraudcontrols.rules.RuleEvaluationConditionResult
import com.fraudcontrols.rules.RuleEvaluationDetail
import com.fraudcontrols.rules.RuleEvaluationResult
import com.fraudcontrols.rules.RuleMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeContractsTest {
    @Test
    fun `decision event contract is versioned and preserves the audit score shape`() {
        val json = sampleDecision().toDecisionEventJsonObject()

        assertEquals("1", json["schema_version"]?.jsonPrimitive?.content)
        assertEquals("evt-1", json["event_id"]?.jsonPrimitive?.content)
        assertEquals("HOLD", json["action"]?.jsonPrimitive?.content)
        assertEquals("velocity_spike", json["reason_codes"]?.jsonArray?.single()?.jsonPrimitive?.content)
        val score = json["score"]?.jsonObject ?: error("score missing")
        assertEquals("0.42", score["score"]?.jsonPrimitive?.content)
        assertEquals("0.5", score["raw_score"]?.jsonPrimitive?.content)
        assertEquals("fixed-v1", score["model_version"]?.jsonPrimitive?.content)
        assertEquals("true", score["degraded"]?.jsonPrimitive?.content)
    }

    @Test
    fun `decision event reader ignores unknown future fields for the current schema version`() {
        val payload = sampleDecision()
            .toDecisionEventJsonString()
            .replace("\"decided_at\"", "\"future_extra\":{\"nested\":true},\"decided_at\"")

        val parsed = parseDecisionEventContract(payload)

        assertEquals("evt-1", parsed["event_id"]?.jsonPrimitive?.content)
        assertEquals("HOLD", parsed["action"]?.jsonPrimitive?.content)
    }

    @Test
    fun `rule evaluation event contract is versioned with full match diagnostics`() {
        val json = sampleRuleEvaluation().toRuleEvaluationEventJsonObject()

        assertEquals("1", json["schema_version"]?.jsonPrimitive?.content)
        assertEquals("evt-1", json["event_id"]?.jsonPrimitive?.content)
        val match = json["matches"]?.jsonArray?.single()?.jsonObject ?: error("match missing")
        assertEquals("velocity-spike", match["rule_id"]?.jsonPrimitive?.content)
        assertEquals("1", match["rule_version"]?.jsonPrimitive?.content)
        assertEquals("ENFORCE", match["mode"]?.jsonPrimitive?.content)
        assertEquals("REVIEW_QUEUE", match["action_type"]?.jsonPrimitive?.content)
        val resolved = json["resolved_action"]?.jsonObject ?: error("resolved action missing")
        assertEquals("HOLD", resolved["decision_action"]?.jsonPrimitive?.content)
        val evaluations = json["evaluations"]?.jsonArray ?: error("evaluations missing")
        assertEquals(4, evaluations.size)
        val nonMatch = evaluations[1].jsonObject
        assertEquals("low-amount-allow", nonMatch["rule_id"]?.jsonPrimitive?.content)
        assertEquals("NOT_MATCHED", nonMatch["condition_result"]?.jsonPrimitive?.content)
        assertEquals("ALLOW", nonMatch["action"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        val unavailable = evaluations[2].jsonObject
        assertEquals("UNAVAILABLE", unavailable["condition_result"]?.jsonPrimitive?.content)
        assertEquals(
            "feature fraud_model_score unavailable: redis timeout",
            unavailable["skipped_reason"]?.jsonPrimitive?.content,
        )
        assertEquals(
            "redis timeout",
            unavailable["feature_values"]
                ?.jsonObject
                ?.get(FraudFeatureNames.FRAUD_MODEL_SCORE)
                ?.jsonObject
                ?.get("value")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals("DISABLED", evaluations[3].jsonObject["condition_result"]?.jsonPrimitive?.content)
        val conflictResolution = json["conflict_resolution"]?.jsonObject ?: error("conflict resolution missing")
        assertEquals(
            "enforce_matches_by_priority_desc_severity_desc_rule_id_asc",
            conflictResolution["strategy"]?.jsonPrimitive?.content,
        )
        assertEquals(
            "velocity-spike",
            conflictResolution["selected"]?.jsonObject?.get("rule_id")?.jsonPrimitive?.content,
        )
        assertEquals(
            listOf("velocity-spike"),
            conflictResolution["candidates"]?.jsonArray?.map { it.jsonObject["rule_id"]?.jsonPrimitive?.content },
        )
    }

    @Test
    fun `rule evaluation reader ignores unknown future fields for the current schema version`() {
        val payload = sampleRuleEvaluation()
            .toRuleEvaluationEventJsonString()
            .replace("\"matches\"", "\"future_extra\":\"ignored\",\"matches\"")

        val parsed = parseRuleEvaluationEventContract(payload)

        assertEquals("evt-1", parsed["event_id"]?.jsonPrimitive?.content)
        assertEquals("velocity-spike", parsed["matches"]?.jsonArray?.single()?.jsonObject?.get("rule_id")?.jsonPrimitive?.content)
    }

    @Test
    fun `decision audit row contract is versioned and embeds reconstructable JSON`() {
        val row = sampleRecord().toDecisionAuditRowContract()

        assertEquals(RuntimeContractVersions.DECISION_AUDIT_ROW, row.schemaVersion)
        assertEquals("evt-1", row.eventId)
        assertEquals("HOLD", row.action)
        assertEquals(listOf("velocity_spike"), row.reasonCodes)
        assertEquals(listOf("velocity-spike"), row.ruleEvaluationIds)
        assertEquals("fixed-v1", row.modelVersion)

        val score = Json.parseToJsonElement(row.scoreJson).jsonObject
        assertEquals("fixed-v1", score["model_version"]?.jsonPrimitive?.content)
        val features = Json.parseToJsonElement(row.featuresJson).jsonObject
        assertEquals("evt-1", features["event_id"]?.jsonPrimitive?.content)
        assertEquals("number", features["values"]?.jsonObject?.get(FraudFeatureNames.AMOUNT)?.jsonObject?.get("type")?.jsonPrimitive?.content)
        val ruleEvaluation = parseRuleEvaluationEventContract(row.ruleEvaluationJson)
        assertEquals("velocity-spike", ruleEvaluation["matches"]?.jsonArray?.single()?.jsonObject?.get("rule_id")?.jsonPrimitive?.content)
        assertEquals(4, ruleEvaluation["evaluations"]?.jsonArray?.size)
        assertEquals(
            "trust_safety_l2",
            ruleEvaluation["evaluations"]
                ?.jsonArray
                ?.first()
                ?.jsonObject
                ?.get("action")
                ?.jsonObject
                ?.get("queue")
                ?.jsonPrimitive
                ?.content,
        )
    }

    private fun sampleRecord(): DecisionRecord = DecisionRecord(
        decision = sampleDecision(),
        features = FeatureSnapshot(
            eventId = EventId("evt-1"),
            values = linkedMapOf(
                FraudFeatureNames.AMOUNT to FeatureValue.NumberValue(25.50),
                FraudFeatureNames.FRAUD_MODEL_SCORE to FeatureValue.ScoreValue(sampleScore()),
            ),
        ),
        ruleEvaluation = sampleRuleEvaluation(),
        score = sampleScore(),
    )

    private fun sampleDecision(): Decision = Decision(
        eventId = EventId("evt-1"),
        action = DecisionAction.HOLD,
        reasonCodes = listOf(ReasonCode("velocity_spike")),
        score = sampleScore(),
        ruleEvaluationIds = listOf("velocity-spike"),
        decidedAt = Instant.parse("2026-01-01T12:00:05Z"),
    )

    private fun sampleRuleEvaluation(): RuleEvaluationResult {
        val reviewAction = RuleAction(
            type = RuleActionType.REVIEW_QUEUE,
            reasonCode = ReasonCode("velocity_spike"),
            reversible = true,
            queue = "trust_safety_l2",
        )
        val allowAction = RuleAction(type = RuleActionType.ALLOW)
        val challengeAction = RuleAction(
            type = RuleActionType.CHALLENGE,
            reasonCode = ReasonCode("MODEL_UNAVAILABLE"),
        )
        return RuleEvaluationResult(
            eventId = EventId("evt-1"),
            evaluations = listOf(
                RuleEvaluationDetail(
                    ruleId = "velocity-spike",
                    ruleVersion = 1,
                    mode = RuleMode.ENFORCE,
                    priority = 100,
                    conditionResult = RuleEvaluationConditionResult.MATCHED,
                    action = reviewAction,
                    featureValues = linkedMapOf(
                        FraudFeatureNames.AMOUNT to FeatureValue.NumberValue(25.50),
                        FraudFeatureNames.FRAUD_MODEL_SCORE to FeatureValue.ScoreValue(sampleScore()),
                    ),
                ),
                RuleEvaluationDetail(
                    ruleId = "low-amount-allow",
                    ruleVersion = 2,
                    mode = RuleMode.ENFORCE,
                    priority = 200,
                    conditionResult = RuleEvaluationConditionResult.NOT_MATCHED,
                    action = allowAction,
                    featureValues = linkedMapOf(
                        FraudFeatureNames.AMOUNT to FeatureValue.NumberValue(25.50),
                    ),
                ),
                RuleEvaluationDetail(
                    ruleId = "model-unavailable",
                    ruleVersion = 3,
                    mode = RuleMode.ENFORCE,
                    priority = 150,
                    conditionResult = RuleEvaluationConditionResult.UNAVAILABLE,
                    action = challengeAction,
                    featureValues = linkedMapOf(
                        FraudFeatureNames.FRAUD_MODEL_SCORE to FeatureValue.Unavailable("redis timeout"),
                    ),
                    skippedReason = "feature fraud_model_score unavailable: redis timeout",
                ),
                RuleEvaluationDetail(
                    ruleId = "disabled-shadow-rule",
                    ruleVersion = 4,
                    mode = RuleMode.DISABLED,
                    priority = 50,
                    conditionResult = RuleEvaluationConditionResult.DISABLED,
                    action = RuleAction(type = RuleActionType.TAG, tag = "disabled"),
                    featureValues = linkedMapOf(
                        FraudFeatureNames.AMOUNT to FeatureValue.NumberValue(25.50),
                    ),
                    skippedReason = "rule is disabled",
                ),
            ),
            resolvedAction = ResolvedRuleAction(
                ruleId = "velocity-spike",
                ruleVersion = 1,
                decisionAction = DecisionAction.HOLD,
                action = reviewAction,
                priority = 100,
            ),
        )
    }

    private fun sampleScore(): ScoreResult = ScoreResult(
        score = 0.42,
        rawScore = 0.5,
        contributingFactors = listOf(Factor(name = "fixed", contribution = 0.42)),
        modelVersion = "fixed-v1",
        latencyMs = 1.5,
        degraded = true,
    )

    @Suppress("unused")
    private fun sampleEvent(): TransactionEvent = TransactionEvent(
        eventId = EventId("evt-1"),
        timestamp = Instant.parse("2026-01-01T12:00:00Z"),
        senderId = CustomerId("sender-1"),
        recipientId = CustomerId("recipient-1"),
        amount = Money.usd("25.50"),
        transactionType = TransactionType.P2P_SEND,
        senderDeviceFingerprint = DeviceFingerprint("device-1"),
        senderGeo = GeoPoint(latitude = 43.6532, longitude = -79.3832),
        senderBalanceBefore = BigDecimal("100.00"),
        senderBalanceAfter = BigDecimal("74.50"),
        recipientBalanceBefore = BigDecimal("50.00"),
        recipientBalanceAfter = BigDecimal("75.50"),
        senderAccountAgeDays = 0.125,
        recipientAccountAgeDays = 120.5,
        isNewCounterparty = true,
    )
}
