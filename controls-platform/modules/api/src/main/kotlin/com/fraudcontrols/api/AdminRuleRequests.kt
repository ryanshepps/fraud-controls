package com.fraudcontrols.api

import com.fraudcontrols.core.ReasonCode
import com.fraudcontrols.rules.RuleAction
import com.fraudcontrols.rules.RuleActionType
import com.fraudcontrols.rules.RuleDefinition
import com.fraudcontrols.rules.RuleMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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

private fun RuleActionRequest.toRuleAction(): RuleAction = mapApiJsonException {
    RuleAction(
        type = type.toRuleActionType(),
        reasonCode = reasonCode.requireNotBlankIfPresent("reason_code")?.let(::ReasonCode),
        reversible = reversible,
        queue = queue.requireNotBlankIfPresent("queue"),
        tag = tag.requireNotBlankIfPresent("tag"),
    )
}

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
