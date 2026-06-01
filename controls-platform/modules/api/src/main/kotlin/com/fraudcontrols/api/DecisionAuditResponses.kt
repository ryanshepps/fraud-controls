package com.fraudcontrols.api

import com.fraudcontrols.decisioning.contracts.DecisionAuditRowContract
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class DecisionAuditResponse(
    @SerialName("schema_version")
    val schemaVersion: Int,
    @SerialName("event_id")
    val eventId: String,
    @SerialName("decided_at")
    val decidedAt: String,
    val action: String,
    @SerialName("reason_codes")
    val reasonCodes: List<String>,
    val score: Double,
    @SerialName("model_version")
    val modelVersion: String,
    @SerialName("score_json")
    val scoreJson: JsonObject,
    @SerialName("rule_evaluation_ids")
    val ruleEvaluationIds: List<String>,
    @SerialName("features_json")
    val featuresJson: JsonObject,
    @SerialName("rule_evaluation_json")
    val ruleEvaluationJson: JsonObject,
)

internal fun DecisionAuditRowContract.toApiResponse(): DecisionAuditResponse = DecisionAuditResponse(
    schemaVersion = schemaVersion,
    eventId = eventId,
    decidedAt = decidedAt,
    action = action,
    reasonCodes = reasonCodes,
    score = score,
    modelVersion = modelVersion,
    scoreJson = parseJsonObjectString(scoreJson),
    ruleEvaluationIds = ruleEvaluationIds,
    featuresJson = parseJsonObjectString(featuresJson),
    ruleEvaluationJson = parseJsonObjectString(ruleEvaluationJson),
)

private fun parseJsonObjectString(payload: String): JsonObject = apiJson.parseToJsonElement(payload) as? JsonObject
    ?: throw ApiJsonException("expected JSON object")
