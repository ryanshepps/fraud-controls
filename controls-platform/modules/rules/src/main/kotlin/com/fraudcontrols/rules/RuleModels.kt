package com.fraudcontrols.rules

import com.fraudcontrols.core.DecisionAction
import com.fraudcontrols.core.ReasonCode

data class RuleSet(
    val version: Int,
    val rules: List<RuleDefinition>,
) {
    init {
        require(version > 0) { "rule set version must be positive" }
        require(rules.map { it.id }.toSet().size == rules.size) { "rule ids must be unique" }
    }
}

data class RuleDefinition(
    val id: String,
    val version: Int,
    val description: String? = null,
    val enabled: Boolean = true,
    val mode: RuleMode = RuleMode.ENFORCE,
    val priority: Int = 0,
    val condition: RuleCondition,
    val action: RuleAction,
) {
    init {
        require(id.isNotBlank()) { "rule id must not be blank" }
        require(version > 0) { "rule version must be positive" }
        require(description == null || description.isNotBlank()) { "rule description must not be blank" }
    }

    val effectiveMode: RuleMode
        get() = if (enabled) mode else RuleMode.DISABLED
}

enum class RuleMode {
    SHADOW,
    ENFORCE,
    DISABLED,
}

sealed interface RuleCondition {
    data class Comparison(
        val featureName: String,
        val operator: ComparisonOperator,
        val value: RuleValue,
    ) : RuleCondition {
        init {
            require(featureName.isNotBlank()) { "feature name must not be blank" }
        }
    }

    data class All(
        val conditions: List<RuleCondition>,
    ) : RuleCondition {
        init {
            require(conditions.isNotEmpty()) { "all condition must include at least one condition" }
        }
    }

    data class Any(
        val conditions: List<RuleCondition>,
    ) : RuleCondition {
        init {
            require(conditions.isNotEmpty()) { "any condition must include at least one condition" }
        }
    }

    data class Not(
        val condition: RuleCondition,
    ) : RuleCondition
}

fun RuleCondition.featureNames(): Set<String> {
    val names = linkedSetOf<String>()
    collectFeatureNames(names)
    return names
}

private fun RuleCondition.collectFeatureNames(names: MutableSet<String>) {
    when (this) {
        is RuleCondition.All -> conditions.forEach { it.collectFeatureNames(names) }
        is RuleCondition.Any -> conditions.forEach { it.collectFeatureNames(names) }
        is RuleCondition.Comparison -> names += featureName
        is RuleCondition.Not -> condition.collectFeatureNames(names)
    }
}

enum class ComparisonOperator {
    EQ,
    NEQ,
    LT,
    LTE,
    GT,
    GTE,
    IN,
    NOT_IN,
}

sealed interface RuleValue {
    data class NumberValue(val value: Double) : RuleValue {
        init {
            require(value.isFinite()) { "rule number value must be finite" }
        }
    }

    data class BooleanValue(val value: Boolean) : RuleValue
    data class TextValue(val value: String) : RuleValue {
        init {
            require(value.isNotBlank()) { "rule text value must not be blank" }
        }
    }

    data class SetValue(val values: Set<RuleValue>) : RuleValue {
        init {
            require(values.isNotEmpty()) { "rule set value must not be empty" }
            require(values.none { it is SetValue }) { "nested rule set values are not supported" }
        }
    }
}

data class RuleAction(
    val type: RuleActionType,
    val reasonCode: ReasonCode? = null,
    val reversible: Boolean = false,
    val queue: String? = null,
    val tag: String? = null,
) {
    init {
        require(queue == null || queue.isNotBlank()) { "review queue must not be blank" }
        require(tag == null || tag.isNotBlank()) { "tag must not be blank" }
        require(type != RuleActionType.REVIEW_QUEUE || queue != null) { "review_queue action requires queue" }
        require(type != RuleActionType.TAG || tag != null) { "tag action requires tag" }
    }
}

enum class RuleActionType {
    ALLOW,
    BLOCK,
    CHALLENGE,
    REVIEW_QUEUE,
    TAG,
}

fun RuleAction.decisionAction(): DecisionAction? =
    when (type) {
        RuleActionType.ALLOW -> DecisionAction.ALLOW
        RuleActionType.BLOCK -> DecisionAction.DENY
        RuleActionType.CHALLENGE -> DecisionAction.CHALLENGE
        RuleActionType.REVIEW_QUEUE -> DecisionAction.HOLD
        RuleActionType.TAG -> null
    }
