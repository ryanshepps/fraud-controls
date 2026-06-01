package com.fraudcontrols.api

import com.fraudcontrols.rules.ComparisonOperator
import com.fraudcontrols.rules.RuleCondition
import com.fraudcontrols.rules.RuleMode
import com.fraudcontrols.rules.RuleValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

internal val apiJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}

internal fun parseConditionJson(raw: JsonObject): RuleCondition = when {
    "all" in raw -> RuleCondition.All(raw.requiredArray("all").map { parseConditionJson(it.requiredObject("all condition")) })
    "any" in raw -> RuleCondition.Any(raw.requiredArray("any").map { parseConditionJson(it.requiredObject("any condition")) })
    "not" in raw -> RuleCondition.Not(parseConditionJson(raw.requiredObject("not")))
    "feature" in raw ->
        RuleCondition.Comparison(
            featureName = raw.requiredString("feature"),
            operator = raw.requiredString("op").toComparisonOperator(),
            value = parseRuleValue(raw["value"] ?: throw ApiJsonException("condition value is required")),
        )
    else -> throw ApiJsonException("condition must contain all, any, not, or feature")
}

internal fun RuleCondition.toJsonObject(): JsonObject = when (this) {
    is RuleCondition.All ->
        buildJsonObject {
            put("all", JsonArray(conditions.map { it.toJsonObject() }))
        }
    is RuleCondition.Any ->
        buildJsonObject {
            put("any", JsonArray(conditions.map { it.toJsonObject() }))
        }
    is RuleCondition.Not ->
        buildJsonObject {
            put("not", condition.toJsonObject())
        }
    is RuleCondition.Comparison ->
        buildJsonObject {
            put("feature", featureName)
            put("op", operator.wireName())
            put("value", value.toJsonElement())
        }
}

internal fun String.toRuleMode(): RuleMode = when (this) {
    "shadow" -> RuleMode.SHADOW
    "enforce" -> RuleMode.ENFORCE
    "disabled" -> RuleMode.DISABLED
    else -> throw ApiJsonException("unsupported rule mode: $this")
}

internal fun RuleMode.wireName(): String = when (this) {
    RuleMode.DISABLED -> "disabled"
    RuleMode.ENFORCE -> "enforce"
    RuleMode.SHADOW -> "shadow"
}

private fun parseRuleValue(raw: JsonElement): RuleValue = when (raw) {
    is JsonArray -> RuleValue.SetValue(raw.map(::parseRuleValue).toSet())
    is JsonObject -> throw ApiJsonException("rule value must be scalar or array")
    JsonNull -> throw ApiJsonException("rule value must not be null")
    is JsonPrimitive ->
        when {
            raw.booleanOrNull != null -> RuleValue.BooleanValue(raw.booleanOrNull == true)
            raw.doubleOrNull != null -> RuleValue.NumberValue(raw.doubleOrNull ?: error("unreachable"))
            raw.contentOrNull != null -> RuleValue.TextValue(raw.content)
            else -> throw ApiJsonException("unsupported rule value")
        }
}

private fun RuleValue.toJsonElement(): JsonElement = when (this) {
    is RuleValue.BooleanValue -> JsonPrimitive(value)
    is RuleValue.NumberValue -> JsonPrimitive(value)
    is RuleValue.SetValue -> JsonArray(values.map { it.toJsonElement() })
    is RuleValue.TextValue -> JsonPrimitive(value)
}

private fun String.toComparisonOperator(): ComparisonOperator = when (this) {
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

private fun ComparisonOperator.wireName(): String = when (this) {
    ComparisonOperator.EQ -> "eq"
    ComparisonOperator.NEQ -> "neq"
    ComparisonOperator.LT -> "lt"
    ComparisonOperator.LTE -> "lte"
    ComparisonOperator.GT -> "gt"
    ComparisonOperator.GTE -> "gte"
    ComparisonOperator.IN -> "in"
    ComparisonOperator.NOT_IN -> "not_in"
}

private fun JsonElement.requiredObject(name: String): JsonObject = this as? JsonObject
    ?: throw ApiJsonException("$name must be an object")

private fun JsonObject.requiredObject(name: String): JsonObject = this[name] as? JsonObject
    ?: throw ApiJsonException("$name must be an object")

private fun JsonObject.requiredArray(name: String): JsonArray = this[name] as? JsonArray
    ?: throw ApiJsonException("$name must be an array")

private fun JsonObject.requiredString(name: String): String {
    val value = this[name] ?: throw ApiJsonException("$name is required")
    val primitive = value as? JsonPrimitive ?: throw ApiJsonException("$name must be a string")
    val text = primitive.contentOrNull ?: throw ApiJsonException("$name must be a string")
    if (text.isBlank()) {
        throw ApiJsonException("$name must not be blank")
    }
    return text
}

internal class ApiJsonException(
    message: String,
) : IllegalArgumentException(message)
