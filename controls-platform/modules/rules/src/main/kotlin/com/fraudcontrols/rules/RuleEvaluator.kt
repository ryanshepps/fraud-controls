package com.fraudcontrols.rules

import com.fraudcontrols.core.DecisionAction
import com.fraudcontrols.core.EventId
import com.fraudcontrols.core.FeatureSnapshot
import com.fraudcontrols.core.FeatureValue
import com.fraudcontrols.core.ReasonCode

class RuleEvaluator {
    fun evaluate(
        snapshot: FeatureSnapshot,
        rules: List<RuleDefinition>,
    ): RuleEvaluationResult {
        val matches = mutableListOf<RuleMatch>()
        val skipped = mutableListOf<SkippedRule>()

        for (rule in rules) {
            when (val conditionResult = evaluateCondition(rule.condition, snapshot)) {
                ConditionResult.Matched -> matches += RuleMatch(rule.id, rule.action, rule.reasonCode)
                ConditionResult.NotMatched -> Unit
                is ConditionResult.Unavailable -> skipped += SkippedRule(rule.id, conditionResult.reason)
            }
        }

        return RuleEvaluationResult(
            eventId = snapshot.eventId,
            matches = matches,
            skipped = skipped,
        )
    }

    private fun evaluateCondition(
        condition: RuleCondition,
        snapshot: FeatureSnapshot,
    ): ConditionResult =
        when (condition) {
            is RuleCondition.All -> evaluateAll(condition.conditions, snapshot)
            is RuleCondition.BooleanEquals -> evaluateBoolean(condition, snapshot)
            is RuleCondition.NumberCompare -> evaluateNumber(condition, snapshot)
            is RuleCondition.TextEquals -> evaluateText(condition, snapshot)
        }

    private fun evaluateAll(
        conditions: List<RuleCondition>,
        snapshot: FeatureSnapshot,
    ): ConditionResult {
        val unavailableReasons = mutableListOf<String>()

        for (condition in conditions) {
            when (val result = evaluateCondition(condition, snapshot)) {
                ConditionResult.Matched -> Unit
                ConditionResult.NotMatched -> return ConditionResult.NotMatched
                is ConditionResult.Unavailable -> unavailableReasons += result.reason
            }
        }

        return if (unavailableReasons.isEmpty()) {
            ConditionResult.Matched
        } else {
            ConditionResult.Unavailable(unavailableReasons.joinToString("; "))
        }
    }

    private fun evaluateNumber(
        condition: RuleCondition.NumberCompare,
        snapshot: FeatureSnapshot,
    ): ConditionResult {
        val value = snapshot.values[condition.featureName]
            ?: return ConditionResult.Unavailable("missing numeric feature: ${condition.featureName}")
        if (value !is FeatureValue.NumberValue) {
            return ConditionResult.Unavailable(
                "feature ${condition.featureName} expected numeric but was ${value.typeName()}",
            )
        }

        val matched = when (condition.operator) {
            NumericOperator.GREATER_THAN -> value.value > condition.threshold
            NumericOperator.GREATER_THAN_OR_EQUAL -> value.value >= condition.threshold
            NumericOperator.LESS_THAN -> value.value < condition.threshold
            NumericOperator.LESS_THAN_OR_EQUAL -> value.value <= condition.threshold
            NumericOperator.EQUAL -> value.value == condition.threshold
        }

        return if (matched) ConditionResult.Matched else ConditionResult.NotMatched
    }

    private fun evaluateBoolean(
        condition: RuleCondition.BooleanEquals,
        snapshot: FeatureSnapshot,
    ): ConditionResult {
        val value = snapshot.values[condition.featureName]
            ?: return ConditionResult.Unavailable("missing boolean feature: ${condition.featureName}")
        if (value !is FeatureValue.BooleanValue) {
            return ConditionResult.Unavailable(
                "feature ${condition.featureName} expected boolean but was ${value.typeName()}",
            )
        }

        return if (value.value == condition.expected) ConditionResult.Matched else ConditionResult.NotMatched
    }

    private fun evaluateText(
        condition: RuleCondition.TextEquals,
        snapshot: FeatureSnapshot,
    ): ConditionResult {
        val value = snapshot.values[condition.featureName]
            ?: return ConditionResult.Unavailable("missing text feature: ${condition.featureName}")
        if (value !is FeatureValue.TextValue) {
            return ConditionResult.Unavailable(
                "feature ${condition.featureName} expected text but was ${value.typeName()}",
            )
        }

        return if (value.value == condition.expected) ConditionResult.Matched else ConditionResult.NotMatched
    }
}

data class RuleDefinition(
    val id: String,
    val action: DecisionAction,
    val reasonCode: ReasonCode,
    val condition: RuleCondition,
) {
    init {
        require(id.isNotBlank()) { "rule id must not be blank" }
    }
}

sealed interface RuleCondition {
    data class NumberCompare(
        val featureName: String,
        val operator: NumericOperator,
        val threshold: Double,
    ) : RuleCondition {
        init {
            require(featureName.isNotBlank()) { "feature name must not be blank" }
            require(threshold.isFinite()) { "numeric threshold must be finite" }
        }
    }

    data class BooleanEquals(
        val featureName: String,
        val expected: Boolean,
    ) : RuleCondition {
        init {
            require(featureName.isNotBlank()) { "feature name must not be blank" }
        }
    }

    data class TextEquals(
        val featureName: String,
        val expected: String,
    ) : RuleCondition {
        init {
            require(featureName.isNotBlank()) { "feature name must not be blank" }
            require(expected.isNotBlank()) { "expected text must not be blank" }
        }
    }

    data class All(
        val conditions: List<RuleCondition>,
    ) : RuleCondition {
        init {
            require(conditions.isNotEmpty()) { "all condition must include at least one condition" }
        }
    }
}

enum class NumericOperator {
    GREATER_THAN,
    GREATER_THAN_OR_EQUAL,
    LESS_THAN,
    LESS_THAN_OR_EQUAL,
    EQUAL,
}

data class RuleEvaluationResult(
    val eventId: EventId,
    val matches: List<RuleMatch>,
    val skipped: List<SkippedRule>,
)

data class RuleMatch(
    val ruleId: String,
    val action: DecisionAction,
    val reasonCode: ReasonCode,
)

data class SkippedRule(
    val ruleId: String,
    val reason: String,
) {
    init {
        require(reason.isNotBlank()) { "skipped rule reason must not be blank" }
    }
}

private sealed interface ConditionResult {
    data object Matched : ConditionResult
    data object NotMatched : ConditionResult
    data class Unavailable(val reason: String) : ConditionResult
}

private fun FeatureValue.typeName(): String =
    when (this) {
        is FeatureValue.BooleanValue -> "boolean"
        is FeatureValue.Missing -> "missing"
        is FeatureValue.NumberValue -> "numeric"
        is FeatureValue.SetValue -> "set"
        is FeatureValue.TextValue -> "text"
    }
