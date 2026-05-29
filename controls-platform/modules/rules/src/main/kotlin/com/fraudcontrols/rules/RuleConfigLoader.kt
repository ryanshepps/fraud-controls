package com.fraudcontrols.rules

import com.fraudcontrols.core.ReasonCode
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.Reader
import java.nio.file.Files
import java.nio.file.Path

class RuleConfigLoader {
    private val yaml = Load(LoadSettings.builder().build())

    fun load(path: Path): RuleSet = Files.newBufferedReader(path).use(::load)

    fun load(reader: Reader): RuleSet {
        val root = yaml.loadFromReader(reader).asMap("root")
        val ruleSetVersion = root.int("version") ?: throw RuleConfigException("rules.version is required")
        val rawRules = root.list("rules") ?: throw RuleConfigException("rules.rules is required")
        val rules = rawRules.mapIndexed { index, rawRule ->
            parseRule(rawRule.asMap("rules[$index]"), defaultVersion = ruleSetVersion)
        }
        return RuleSet(version = ruleSetVersion, rules = rules)
    }

    private fun parseRule(
        raw: Map<String, Any?>,
        defaultVersion: Int,
    ): RuleDefinition {
        val enabled = raw.boolean("enabled") ?: true
        val parsedMode = raw.string("mode")?.toRuleMode() ?: RuleMode.ENFORCE
        return RuleDefinition(
            id = raw.requiredString("id"),
            version = raw.int("version") ?: defaultVersion,
            description = raw.string("description"),
            enabled = enabled,
            mode = if (enabled) parsedMode else RuleMode.DISABLED,
            priority = raw.int("priority") ?: 0,
            condition = parseCondition(raw.requiredMap("when"), "rule ${raw.requiredString("id")} when"),
            action = parseAction(raw.requiredMap("action")),
        )
    }

    private fun parseCondition(
        raw: Map<String, Any?>,
        path: String,
    ): RuleCondition = when {
        raw.containsKey("all") -> RuleCondition.All(parseConditionList(raw.requiredList("all"), "$path.all"))
        raw.containsKey("any") -> RuleCondition.Any(parseConditionList(raw.requiredList("any"), "$path.any"))
        raw.containsKey("not") -> RuleCondition.Not(parseCondition(raw.requiredMap("not"), "$path.not"))
        raw.containsKey("feature") -> RuleCondition.Comparison(
            featureName = raw.requiredString("feature"),
            operator = raw.requiredString("op").toComparisonOperator(),
            value = parseRuleValue(raw["value"] ?: throw RuleConfigException("$path.value is required")),
        )
        else -> throw RuleConfigException("$path must contain all, any, not, or feature")
    }

    private fun parseConditionList(
        raw: List<Any?>,
        path: String,
    ): List<RuleCondition> {
        if (raw.isEmpty()) {
            throw RuleConfigException("$path must contain at least one condition")
        }
        return raw.mapIndexed { index, item ->
            parseCondition(item.asMap("$path[$index]"), "$path[$index]")
        }
    }

    private fun parseAction(raw: Map<String, Any?>): RuleAction {
        val type = raw.requiredString("type").toRuleActionType()
        return RuleAction(
            type = type,
            reasonCode = raw.string("reason_code")?.let(::ReasonCode),
            reversible = raw.boolean("reversible") ?: false,
            queue = raw.string("queue"),
            tag = raw.string("tag"),
        )
    }

    private fun parseRuleValue(raw: Any?): RuleValue = when (raw) {
        is Boolean -> RuleValue.BooleanValue(raw)
        is Int -> RuleValue.NumberValue(raw.toDouble())
        is Long -> RuleValue.NumberValue(raw.toDouble())
        is Double -> RuleValue.NumberValue(raw)
        is Float -> RuleValue.NumberValue(raw.toDouble())
        is Number -> RuleValue.NumberValue(raw.toDouble())
        is String -> RuleValue.TextValue(raw)
        is List<*> -> RuleValue.SetValue(raw.map(::parseRuleValue).toSet())
        else -> throw RuleConfigException("unsupported rule value: ${raw?.let { it::class.simpleName } ?: "null"}")
    }

    private fun String.toRuleMode(): RuleMode = when (this) {
        "shadow" -> RuleMode.SHADOW
        "enforce" -> RuleMode.ENFORCE
        "disabled" -> RuleMode.DISABLED
        else -> throw RuleConfigException("unsupported rule mode: $this")
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
        else -> throw RuleConfigException("unsupported comparison operator: $this")
    }

    private fun String.toRuleActionType(): RuleActionType = when (this) {
        "allow" -> RuleActionType.ALLOW
        "block" -> RuleActionType.BLOCK
        "challenge" -> RuleActionType.CHALLENGE
        "review_queue" -> RuleActionType.REVIEW_QUEUE
        "tag" -> RuleActionType.TAG
        else -> throw RuleConfigException("unsupported rule action type: $this")
    }
}

class RuleConfigException(message: String) : IllegalArgumentException(message)

private fun Any?.asMap(path: String): Map<String, Any?> = (this as? Map<*, *>)
    ?.mapKeys { (key, _) ->
        key as? String ?: throw RuleConfigException("$path keys must be strings")
    }
    ?: throw RuleConfigException("$path must be an object")

private fun Map<String, Any?>.requiredMap(name: String): Map<String, Any?> = this[name].asMap(name)

private fun Map<String, Any?>.requiredList(name: String): List<Any?> = this[name] as? List<Any?> ?: throw RuleConfigException("$name must be a list")

private fun Map<String, Any?>.list(name: String): List<Any?>? = this[name] as? List<Any?>

private fun Map<String, Any?>.requiredString(name: String): String = string(name) ?: throw RuleConfigException("$name is required")

private fun Map<String, Any?>.string(name: String): String? {
    val value = this[name] ?: return null
    val text = value as? String ?: throw RuleConfigException("$name must be a string")
    if (text.isBlank()) {
        throw RuleConfigException("$name must not be blank")
    }
    return text
}

private fun Map<String, Any?>.boolean(name: String): Boolean? {
    val value = this[name] ?: return null
    return value as? Boolean ?: throw RuleConfigException("$name must be a boolean")
}

private fun Map<String, Any?>.int(name: String): Int? {
    val value = this[name] ?: return null
    return when (value) {
        is Int -> value
        is Long -> value.toInt()
        else -> throw RuleConfigException("$name must be an integer")
    }
}
