package com.fraudcontrols.api

import com.fraudcontrols.rules.RuleAction
import com.fraudcontrols.rules.RuleActionType
import com.fraudcontrols.rules.RuleDefinition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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

private fun RuleAction.toResponse(): RuleActionResponse = RuleActionResponse(
    type = type.wireName(),
    reasonCode = reasonCode?.value,
    reversible = reversible,
    queue = queue,
    tag = tag,
)

private fun RuleActionType.wireName(): String = when (this) {
    RuleActionType.ALLOW -> "allow"
    RuleActionType.BLOCK -> "block"
    RuleActionType.CHALLENGE -> "challenge"
    RuleActionType.REVIEW_QUEUE -> "review_queue"
    RuleActionType.TAG -> "tag"
}
