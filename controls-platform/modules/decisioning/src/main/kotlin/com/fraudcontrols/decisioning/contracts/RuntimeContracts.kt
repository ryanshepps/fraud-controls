package com.fraudcontrols.decisioning.contracts

import com.fraudcontrols.core.Decision
import com.fraudcontrols.core.Factor
import com.fraudcontrols.core.FeatureSnapshot
import com.fraudcontrols.core.FeatureValue
import com.fraudcontrols.core.ScoreResult
import com.fraudcontrols.decisioning.DecisionRecord
import com.fraudcontrols.decisioning.DecisioningResult
import com.fraudcontrols.rules.ResolvedRuleAction
import com.fraudcontrols.rules.RuleAction
import com.fraudcontrols.rules.RuleEvaluationDetail
import com.fraudcontrols.rules.RuleEvaluationResult
import com.fraudcontrols.rules.RuleMatch
import com.fraudcontrols.rules.SkippedRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object RuntimeContractVersions {
    const val DECISION_EVENT = 1
    const val RULE_EVALUATION_EVENT = 1
    const val DECISION_AUDIT_ROW = 1
    const val DECISION_SIDE_EFFECT_ENVELOPE = 1
}

data class DecisionAuditRowContract(
    val schemaVersion: Int,
    val eventId: String,
    val decidedAt: String,
    val action: String,
    val reasonCodes: List<String>,
    val score: Double,
    val modelVersion: String,
    val scoreJson: String,
    val ruleEvaluationIds: List<String>,
    val featuresJson: String,
    val ruleEvaluationJson: String,
)

data class DecisionSideEffectEnvelopeContract(
    val schemaVersion: Int,
    val eventId: String,
    val decisionJson: String,
    val ruleEvaluationJson: String,
    val auditRow: DecisionAuditRowContract,
)

fun Decision.toDecisionEventJsonString(): String = toDecisionEventJsonObject().toString()

fun Decision.toDecisionEventJsonObject(): JsonObject = buildJsonObject {
    put("schema_version", RuntimeContractVersions.DECISION_EVENT)
    put("event_id", eventId.value)
    put("action", action.name)
    put("reason_codes", reasonCodes.map { it.value }.toJsonArray())
    put("score", score.toJsonObject())
    put("rule_evaluation_ids", ruleEvaluationIds.toJsonArray())
    put("decided_at", decidedAt.toString())
}

fun RuleEvaluationResult.toRuleEvaluationEventJsonString(): String = toRuleEvaluationEventJsonObject().toString()

fun RuleEvaluationResult.toRuleEvaluationEventJsonObject(): JsonObject = buildJsonObject {
    put("schema_version", RuntimeContractVersions.RULE_EVALUATION_EVENT)
    put("event_id", eventId.value)
    put("matches", JsonArray(matches.map { it.toJsonObject() }))
    put("skipped", JsonArray(skipped.map { it.toJsonObject() }))
    put("evaluations", JsonArray(evaluations.map { it.toJsonObject() }))
    put("conflict_resolution", conflictResolutionJsonObject())
    resolvedAction?.let { put("resolved_action", it.toJsonObject()) }
}

fun DecisionRecord.toDecisionAuditRowContract(): DecisionAuditRowContract = DecisionAuditRowContract(
    schemaVersion = RuntimeContractVersions.DECISION_AUDIT_ROW,
    eventId = decision.eventId.value,
    decidedAt = decision.decidedAt.toString(),
    action = decision.action.name,
    reasonCodes = decision.reasonCodes.map { it.value },
    score = score.score,
    modelVersion = score.modelVersion,
    scoreJson = score.toJsonObject().toString(),
    ruleEvaluationIds = decision.ruleEvaluationIds,
    featuresJson = features.toJsonObject().toString(),
    ruleEvaluationJson = ruleEvaluation.toRuleEvaluationEventJsonObject().toString(),
)

fun DecisioningResult.toDecisionSideEffectEnvelopeJsonString(): String = buildJsonObject {
    put("schema_version", RuntimeContractVersions.DECISION_SIDE_EFFECT_ENVELOPE)
    put("event_id", decision.eventId.value)
    put("decision", decision.toDecisionEventJsonObject())
    put("rule_evaluation", ruleEvaluation.toRuleEvaluationEventJsonObject())
    put("audit_row", record.toDecisionAuditRowContract().toJsonObject())
}.toString()

fun parseDecisionEventContract(payload: String): JsonObject = parseVersionedObject(payload, RuntimeContractVersions.DECISION_EVENT, "decision event")

fun parseRuleEvaluationEventContract(payload: String): JsonObject = parseVersionedObject(payload, RuntimeContractVersions.RULE_EVALUATION_EVENT, "rule evaluation event")

fun parseDecisionSideEffectEnvelopeContract(payload: String): DecisionSideEffectEnvelopeContract {
    val json = parseVersionedObject(
        payload = payload,
        expectedVersion = RuntimeContractVersions.DECISION_SIDE_EFFECT_ENVELOPE,
        contractName = "decision side effect envelope",
    )
    val eventId = json.requiredString("event_id")
    val decision = json.requiredObject("decision")
    val ruleEvaluation = json.requiredObject("rule_evaluation")
    val auditRow = json.requiredObject("audit_row").toDecisionAuditRowContract()
    require(decision.requiredString("event_id") == eventId) {
        "decision side effect envelope decision event_id must match envelope event_id"
    }
    require(ruleEvaluation.requiredString("event_id") == eventId) {
        "decision side effect envelope rule_evaluation event_id must match envelope event_id"
    }
    require(auditRow.eventId == eventId) {
        "decision side effect envelope audit_row event_id must match envelope event_id"
    }
    return DecisionSideEffectEnvelopeContract(
        schemaVersion = RuntimeContractVersions.DECISION_SIDE_EFFECT_ENVELOPE,
        eventId = eventId,
        decisionJson = decision.toString(),
        ruleEvaluationJson = ruleEvaluation.toString(),
        auditRow = auditRow,
    )
}

private fun parseVersionedObject(
    payload: String,
    expectedVersion: Int,
    contractName: String,
): JsonObject {
    val json = Json.parseToJsonElement(payload).jsonObject
    val version = json["schema_version"]?.jsonPrimitive?.intOrNull
        ?: error("$contractName payload is missing schema_version")
    require(version == expectedVersion) {
        "$contractName schema_version $version is not supported by this reader; expected $expectedVersion"
    }
    return json
}

fun DecisionAuditRowContract.toJsonObject(): JsonObject = buildJsonObject {
    put("schema_version", schemaVersion)
    put("event_id", eventId)
    put("decided_at", decidedAt)
    put("action", action)
    put("reason_codes", reasonCodes.toJsonArray())
    put("score", score)
    put("model_version", modelVersion)
    put("score_json", parseJsonObjectString(scoreJson))
    put("rule_evaluation_ids", ruleEvaluationIds.toJsonArray())
    put("features_json", parseJsonObjectString(featuresJson))
    put("rule_evaluation_json", parseJsonObjectString(ruleEvaluationJson))
}

private fun JsonObject.toDecisionAuditRowContract(): DecisionAuditRowContract = DecisionAuditRowContract(
    schemaVersion = requiredInt("schema_version"),
    eventId = requiredString("event_id"),
    decidedAt = requiredString("decided_at"),
    action = requiredString("action"),
    reasonCodes = requiredStringList("reason_codes"),
    score = requiredDouble("score"),
    modelVersion = requiredString("model_version"),
    scoreJson = requiredObject("score_json").toString(),
    ruleEvaluationIds = requiredStringList("rule_evaluation_ids"),
    featuresJson = requiredObject("features_json").toString(),
    ruleEvaluationJson = requiredObject("rule_evaluation_json").toString(),
)

private fun parseJsonObjectString(payload: String): JsonObject = Json.parseToJsonElement(payload).jsonObject

private fun ScoreResult.toJsonObject(): JsonObject = buildJsonObject {
    put("score", score)
    rawScore?.let { put("raw_score", it) }
    put("contributing_factors", JsonArray(contributingFactors.map { it.toJsonObject() }))
    put("model_version", modelVersion)
    put("latency_ms", latencyMs)
    put("degraded", degraded)
}

private fun Factor.toJsonObject(): JsonObject = buildJsonObject {
    put("name", name)
    put("contribution", contribution)
}

private fun FeatureSnapshot.toJsonObject(): JsonObject = buildJsonObject {
    put("event_id", eventId.value)
    put("values", JsonObject(values.mapValues { (_, value) -> value.toJsonValue() }))
}

private fun FeatureValue.toJsonValue(): JsonObject = when (this) {
    is FeatureValue.BooleanValue -> typedValue("boolean", JsonPrimitive(value))
    is FeatureValue.Missing -> typedValue("missing", JsonPrimitive(reason))
    is FeatureValue.NumberValue -> typedValue("number", JsonPrimitive(value))
    is FeatureValue.ScoreValue -> typedValue("score", result.toJsonObject())
    is FeatureValue.SetValue -> typedValue("set", JsonArray(values.map(::JsonPrimitive)))
    is FeatureValue.TextValue -> typedValue("text", JsonPrimitive(value))
    is FeatureValue.Unavailable -> typedValue("unavailable", JsonPrimitive(reason))
}

private fun typedValue(
    type: String,
    value: JsonElement,
): JsonObject = buildJsonObject {
    put("type", type)
    put("value", value)
}

private fun RuleMatch.toJsonObject(): JsonObject = buildJsonObject {
    put("rule_id", ruleId)
    put("rule_version", ruleVersion)
    put("mode", mode.name)
    put("priority", priority)
    put("action_type", action.type.name)
    put("action", action.toJsonObject())
    action.reasonCode?.let { put("reason_code", it.value) }
}

private fun SkippedRule.toJsonObject(): JsonObject = buildJsonObject {
    put("rule_id", ruleId)
    put("rule_version", ruleVersion)
    put("reason", reason)
}

private fun ResolvedRuleAction.toJsonObject(): JsonObject = buildJsonObject {
    put("rule_id", ruleId)
    put("rule_version", ruleVersion)
    put("decision_action", decisionAction.name)
    put("priority", priority)
    put("action_type", action.type.name)
    put("action", action.toJsonObject())
    action.reasonCode?.let { put("reason_code", it.value) }
}

private fun RuleEvaluationDetail.toJsonObject(): JsonObject = buildJsonObject {
    put("rule_id", ruleId)
    put("rule_version", ruleVersion)
    put("mode", mode.name)
    put("priority", priority)
    put("condition_result", conditionResult.name)
    skippedReason?.let { put("skipped_reason", it) }
    put("action", action.toJsonObject())
    put("feature_values", JsonObject(featureValues.mapValues { (_, value) -> value.toJsonValue() }))
}

private fun RuleEvaluationResult.conflictResolutionJsonObject(): JsonObject = buildJsonObject {
    put("strategy", "enforce_matches_by_priority_desc_severity_desc_rule_id_asc")
    put("candidates", JsonArray(resolutionCandidates.map { it.toJsonObject() }))
    resolvedAction?.let { put("selected", it.toJsonObject()) }
}

private fun RuleAction.toJsonObject(): JsonObject = buildJsonObject {
    put("type", type.name)
    reasonCode?.let { put("reason_code", it.value) }
    put("reversible", reversible)
    queue?.let { put("queue", it) }
    tag?.let { put("tag", it) }
}

private fun Iterable<String>.toJsonArray(): JsonArray = buildJsonArray {
    forEach { add(JsonPrimitive(it)) }
}

private fun JsonObject.requiredObject(name: String): JsonObject = requireNotNull(this[name]) {
    "missing required object field: $name"
}.jsonObject

private fun JsonObject.requiredString(name: String): String = requireNotNull(this[name]?.jsonPrimitive?.content) {
    "missing required string field: $name"
}

private fun JsonObject.requiredInt(name: String): Int = requireNotNull(this[name]?.jsonPrimitive?.intOrNull) {
    "missing required integer field: $name"
}

private fun JsonObject.requiredDouble(name: String): Double = requireNotNull(this[name]?.jsonPrimitive?.double) {
    "missing required number field: $name"
}

private fun JsonObject.requiredStringList(name: String): List<String> = requireNotNull(this[name]?.jsonArray) {
    "missing required string array field: $name"
}.map { value ->
    value.jsonPrimitive.content
}
