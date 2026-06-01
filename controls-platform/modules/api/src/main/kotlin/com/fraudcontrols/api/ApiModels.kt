package com.fraudcontrols.api

import com.fraudcontrols.core.ReasonCode
import com.fraudcontrols.decisioning.contracts.DecisionAuditRowContract
import com.fraudcontrols.rules.RuleAction
import com.fraudcontrols.rules.RuleActionType
import com.fraudcontrols.rules.RuleDefinition
import com.fraudcontrols.rules.RuleMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal val apiJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}

@Serializable
internal data class ApiErrorResponse(
    val error: String,
)

@Serializable
internal data class RuleListResponse(
    val rules: List<RuleResponse>,
)

@Serializable
internal data class RuleHistoryResponse(
    @SerialName("rule_id")
    val ruleId: String,
    val versions: List<RuleResponse>,
)

@Serializable
internal data class RuleDefinitionRequest(
    val id: String? = null,
    val actor: String? = null,
    val description: String? = null,
    val enabled: Boolean = true,
    val mode: String? = null,
    val priority: Int = 0,
    @SerialName("when")
    val condition: JsonObject,
    val action: RuleActionRequest,
)

@Serializable
internal data class RuleActionRequest(
    val type: String,
    @SerialName("reason_code")
    val reasonCode: String? = null,
    val reversible: Boolean = false,
    val queue: String? = null,
    val tag: String? = null,
)

@Serializable
internal data class PromotionRequest(
    val actor: String? = null,
    val confirm: Boolean = false,
)

@Serializable
internal data class RuleActorRequest(
    val actor: String? = null,
)

@Serializable
internal data class RuleResponse(
    val id: String,
    val version: Int,
    val description: String? = null,
    val enabled: Boolean,
    val mode: String,
    val priority: Int,
    @SerialName("when")
    val condition: JsonObject,
    val action: RuleActionResponse,
)

@Serializable
internal data class RuleActionResponse(
    val type: String,
    @SerialName("reason_code")
    val reasonCode: String? = null,
    val reversible: Boolean,
    val queue: String? = null,
    val tag: String? = null,
)

@Serializable
internal data class RuleChangeEventResponse(
    @SerialName("rule_id")
    val ruleId: String,
    @SerialName("rule_version")
    val ruleVersion: Int,
    @SerialName("change_type")
    val changeType: String,
    val actor: String,
    @SerialName("occurred_at")
    val occurredAt: String,
    val diff: Map<String, String>,
)

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

internal fun RuleDefinitionRequest.toRuleDefinition(idOverride: String? = null): RuleDefinition {
    val requestedId = idOverride ?: id.requirePresent("id")
    val requestedMode = mode?.toRuleMode() ?: RuleMode.ENFORCE
    return mapApiJsonException {
        RuleDefinition(
            id = requestedId,
            version = 1,
            description = description.requireNotBlankIfPresent("description"),
            enabled = enabled,
            mode = if (enabled) requestedMode else RuleMode.DISABLED,
            priority = priority,
            condition = parseConditionJson(condition),
            action = action.toRuleAction(),
        )
    }
}

internal fun RuleDefinitionRequest.actorOrDefault(): String = actor.normalizedActor()

internal fun PromotionRequest.actorOrDefault(): String = actor.normalizedActor()

internal fun RuleActorRequest.actorOrDefault(): String = actor.normalizedActor()

internal fun RuleDefinition.toResponse(): RuleResponse = RuleResponse(
    id = id,
    version = version,
    description = description,
    enabled = enabled,
    mode = effectiveMode.wireName(),
    priority = priority,
    condition = condition.toJsonObject(),
    action = action.toResponse(),
)

internal fun RuleChangeEvent.toResponse(): RuleChangeEventResponse = RuleChangeEventResponse(
    ruleId = ruleId,
    ruleVersion = ruleVersion,
    changeType = changeType.name.lowercase(),
    actor = actor,
    occurredAt = occurredAt.toString(),
    diff = diff,
)

internal fun RuleChangeEvent.toJsonObject(): JsonObject = apiJson
    .encodeToJsonElement(RuleChangeEventResponse.serializer(), toResponse())
    .let { it as JsonObject }

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

private fun RuleActionRequest.toRuleAction(): RuleAction = mapApiJsonException {
    RuleAction(
        type = type.toRuleActionType(),
        reasonCode = reasonCode.requireNotBlankIfPresent("reason_code")?.let(::ReasonCode),
        reversible = reversible,
        queue = queue.requireNotBlankIfPresent("queue"),
        tag = tag.requireNotBlankIfPresent("tag"),
    )
}

private fun RuleAction.toResponse(): RuleActionResponse = RuleActionResponse(
    type = type.wireName(),
    reasonCode = reasonCode?.value,
    reversible = reversible,
    queue = queue,
    tag = tag,
)

private fun String?.normalizedActor(): String = requireNotBlankIfPresent("actor") ?: "local"

private fun String?.requirePresent(name: String): String = requireNotBlankIfPresent(name) ?: throw ApiJsonException("$name is required")

private fun String?.requireNotBlankIfPresent(name: String): String? {
    val value = this ?: return null
    if (value.isBlank()) {
        throw ApiJsonException("$name must not be blank")
    }
    return value
}

private fun <T> mapApiJsonException(block: () -> T): T = try {
    block()
} catch (error: ApiJsonException) {
    throw error
} catch (error: IllegalArgumentException) {
    throw ApiJsonException(error.message ?: "invalid request")
}

private fun String.toRuleActionType(): RuleActionType = when (this) {
    "allow" -> RuleActionType.ALLOW
    "block" -> RuleActionType.BLOCK
    "challenge" -> RuleActionType.CHALLENGE
    "review_queue" -> RuleActionType.REVIEW_QUEUE
    "tag" -> RuleActionType.TAG
    else -> throw ApiJsonException("unsupported rule action type: $this")
}

private fun RuleActionType.wireName(): String = when (this) {
    RuleActionType.ALLOW -> "allow"
    RuleActionType.BLOCK -> "block"
    RuleActionType.CHALLENGE -> "challenge"
    RuleActionType.REVIEW_QUEUE -> "review_queue"
    RuleActionType.TAG -> "tag"
}

private fun parseJsonObjectString(payload: String): JsonObject = apiJson.parseToJsonElement(payload) as? JsonObject
    ?: throw ApiJsonException("expected JSON object")
