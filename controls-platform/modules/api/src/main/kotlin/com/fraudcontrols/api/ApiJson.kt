package com.fraudcontrols.api

import com.fraudcontrols.core.ReasonCode
import com.fraudcontrols.decisioning.DecisionRecord
import com.fraudcontrols.decisioning.contracts.DecisionAuditRowContract
import com.fraudcontrols.decisioning.contracts.toDecisionAuditRowContract
import com.fraudcontrols.rules.ComparisonOperator
import com.fraudcontrols.rules.RuleAction
import com.fraudcontrols.rules.RuleActionType
import com.fraudcontrols.rules.RuleCondition
import com.fraudcontrols.rules.RuleDefinition
import com.fraudcontrols.rules.RuleMode
import com.fraudcontrols.rules.RuleValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val json = Json

fun parseRuleDefinitionJson(
    payload: String,
    idOverride: String? = null,
    version: Int = 1,
): RuleDefinition {
    val root = parseObject(payload, "rule")
    val ruleId = idOverride ?: root.requiredString("id")
    val enabled = root.optionalBoolean("enabled") ?: true
    val mode = root.optionalString("mode")?.toRuleMode() ?: RuleMode.ENFORCE
    return RuleDefinition(
        id = ruleId,
        version = version,
        description = root.optionalString("description"),
        enabled = enabled,
        mode = if (enabled) mode else RuleMode.DISABLED,
        priority = root.optionalInt("priority") ?: 0,
        condition = parseCondition(root.requiredObject("when")),
        action = parseAction(root.requiredObject("action")),
    )
}

fun parsePromotionConfirmation(payload: String): Boolean =
    parseObject(payload.ifBlank { "{}" }, "promotion")
        .optionalBoolean("confirm") ?: false

fun parseActor(payload: String): String =
    parseObject(payload.ifBlank { "{}" }, "request")
        .optionalString("actor") ?: "local"

fun parseGlobalKillSwitchMode(payload: String): GlobalKillSwitchMode {
    val mode = parseObject(payload, "global kill switch").requiredString("mode")
    return GlobalKillSwitchMode.entries.firstOrNull { it.wireName == mode }
        ?: throw ApiJsonException("unsupported global kill switch mode: $mode")
}

fun RuleDefinition.toJsonObject(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("version", version)
        description?.let { put("description", it) }
        put("enabled", enabled)
        put("mode", effectiveMode.wireName())
        put("priority", priority)
        put("when", condition.toJsonObject())
        put("action", action.toJsonObject())
    }

fun RuleChangeEvent.toJsonObject(): JsonObject =
    buildJsonObject {
        put("rule_id", ruleId)
        put("rule_version", ruleVersion)
        put("change_type", changeType.name.lowercase())
        put("actor", actor)
        put("occurred_at", occurredAt.toString())
        put("diff", JsonObject(diff.mapValues { (_, value) -> JsonPrimitive(value) }))
    }

fun DecisionRecord.toApiJsonObject(): JsonObject =
    toDecisionAuditRowContract().toJsonObject()

fun errorJson(message: String): String =
    buildJsonObject {
        put("error", message)
    }.toString()

private fun DecisionAuditRowContract.toJsonObject(): JsonObject =
    buildJsonObject {
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

private fun parseJsonObjectString(payload: String): JsonObject =
    json.parseToJsonElement(payload).jsonObject

private fun parseCondition(raw: JsonObject): RuleCondition =
    when {
        "all" in raw -> RuleCondition.All(raw.requiredArray("all").map { parseCondition(it.jsonObject) })
        "any" in raw -> RuleCondition.Any(raw.requiredArray("any").map { parseCondition(it.jsonObject) })
        "not" in raw -> RuleCondition.Not(parseCondition(raw.requiredObject("not")))
        "feature" in raw -> RuleCondition.Comparison(
            featureName = raw.requiredString("feature"),
            operator = raw.requiredString("op").toComparisonOperator(),
            value = parseRuleValue(raw["value"] ?: throw ApiJsonException("condition value is required")),
        )
        else -> throw ApiJsonException("condition must contain all, any, not, or feature")
    }

private fun parseAction(raw: JsonObject): RuleAction {
    val type = raw.requiredString("type").toRuleActionType()
    return RuleAction(
        type = type,
        reasonCode = raw.optionalString("reason_code")?.let(::ReasonCode),
        reversible = raw.optionalBoolean("reversible") ?: false,
        queue = raw.optionalString("queue"),
        tag = raw.optionalString("tag"),
    )
}

private fun parseRuleValue(raw: JsonElement): RuleValue =
    when (raw) {
        is JsonArray -> RuleValue.SetValue(raw.map(::parseRuleValue).toSet())
        is JsonObject -> throw ApiJsonException("rule value must be scalar or array")
        JsonNull -> throw ApiJsonException("rule value must not be null")
        is JsonPrimitive -> when {
            raw.booleanOrNull != null -> RuleValue.BooleanValue(raw.booleanOrNull == true)
            raw.doubleOrNull != null -> RuleValue.NumberValue(raw.doubleOrNull ?: error("unreachable"))
            raw.contentOrNull != null -> RuleValue.TextValue(raw.content)
            else -> throw ApiJsonException("unsupported rule value")
        }
    }

private fun RuleCondition.toJsonObject(): JsonObject =
    when (this) {
        is RuleCondition.All -> buildJsonObject {
            put("all", JsonArray(conditions.map { it.toJsonObject() }))
        }
        is RuleCondition.Any -> buildJsonObject {
            put("any", JsonArray(conditions.map { it.toJsonObject() }))
        }
        is RuleCondition.Not -> buildJsonObject {
            put("not", condition.toJsonObject())
        }
        is RuleCondition.Comparison -> buildJsonObject {
            put("feature", featureName)
            put("op", operator.wireName())
            put("value", value.toJsonElement())
        }
    }

private fun RuleAction.toJsonObject(): JsonObject =
    buildJsonObject {
        put("type", type.wireName())
        reasonCode?.let { put("reason_code", it.value) }
        put("reversible", reversible)
        queue?.let { put("queue", it) }
        tag?.let { put("tag", it) }
    }

private fun RuleValue.toJsonElement(): JsonElement =
    when (this) {
        is RuleValue.BooleanValue -> JsonPrimitive(value)
        is RuleValue.NumberValue -> JsonPrimitive(value)
        is RuleValue.SetValue -> JsonArray(values.map { it.toJsonElement() })
        is RuleValue.TextValue -> JsonPrimitive(value)
    }

private fun String.toRuleMode(): RuleMode =
    when (this) {
        "shadow" -> RuleMode.SHADOW
        "enforce" -> RuleMode.ENFORCE
        "disabled" -> RuleMode.DISABLED
        else -> throw ApiJsonException("unsupported rule mode: $this")
    }

private fun RuleMode.wireName(): String =
    when (this) {
        RuleMode.DISABLED -> "disabled"
        RuleMode.ENFORCE -> "enforce"
        RuleMode.SHADOW -> "shadow"
    }

private fun String.toComparisonOperator(): ComparisonOperator =
    when (this) {
        "eq" -> ComparisonOperator.EQ
        "neq" -> ComparisonOperator.NEQ
        "lt" -> ComparisonOperator.LT
        "lte" -> ComparisonOperator.LTE
        "gt" -> ComparisonOperator.GT
        "gte" -> ComparisonOperator.GTE
        "in" -> ComparisonOperator.IN
        "not_in" -> ComparisonOperator.NOT_IN
        else -> throw ApiJsonException("unsupported comparison operator: $this")
    }

private fun ComparisonOperator.wireName(): String =
    when (this) {
        ComparisonOperator.EQ -> "eq"
        ComparisonOperator.NEQ -> "neq"
        ComparisonOperator.LT -> "lt"
        ComparisonOperator.LTE -> "lte"
        ComparisonOperator.GT -> "gt"
        ComparisonOperator.GTE -> "gte"
        ComparisonOperator.IN -> "in"
        ComparisonOperator.NOT_IN -> "not_in"
    }

private fun String.toRuleActionType(): RuleActionType =
    when (this) {
        "allow" -> RuleActionType.ALLOW
        "block" -> RuleActionType.BLOCK
        "challenge" -> RuleActionType.CHALLENGE
        "review_queue" -> RuleActionType.REVIEW_QUEUE
        "tag" -> RuleActionType.TAG
        else -> throw ApiJsonException("unsupported rule action type: $this")
    }

private fun RuleActionType.wireName(): String =
    when (this) {
        RuleActionType.ALLOW -> "allow"
        RuleActionType.BLOCK -> "block"
        RuleActionType.CHALLENGE -> "challenge"
        RuleActionType.REVIEW_QUEUE -> "review_queue"
        RuleActionType.TAG -> "tag"
    }

private fun parseObject(
    payload: String,
    name: String,
): JsonObject =
    try {
        json.parseToJsonElement(payload).jsonObject
    } catch (error: IllegalArgumentException) {
        throw ApiJsonException("$name must be a JSON object")
    }

private fun JsonObject.requiredObject(name: String): JsonObject =
    this[name]?.jsonObject ?: throw ApiJsonException("$name must be an object")

private fun JsonObject.requiredArray(name: String): JsonArray =
    this[name]?.jsonArray ?: throw ApiJsonException("$name must be an array")

private fun JsonObject.requiredString(name: String): String =
    optionalString(name) ?: throw ApiJsonException("$name is required")

private fun JsonObject.optionalString(name: String): String? {
    val value = this[name] ?: return null
    val text = value.jsonPrimitive.contentOrNull ?: throw ApiJsonException("$name must be a string")
    if (text.isBlank()) {
        throw ApiJsonException("$name must not be blank")
    }
    return text
}

private fun JsonObject.optionalBoolean(name: String): Boolean? {
    val value = this[name] ?: return null
    return value.jsonPrimitive.booleanOrNull ?: throw ApiJsonException("$name must be a boolean")
}

private fun JsonObject.optionalInt(name: String): Int? {
    val value = this[name] ?: return null
    return value.jsonPrimitive.intOrNull ?: throw ApiJsonException("$name must be an integer")
}

private fun Iterable<String>.toJsonArray(): JsonArray =
    buildJsonArray {
        forEach { add(JsonPrimitive(it)) }
    }

class ApiJsonException(message: String) : IllegalArgumentException(message)
