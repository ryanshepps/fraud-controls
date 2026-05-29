package com.fraudcontrols.rules

import com.fraudcontrols.core.DecisionAction
import com.fraudcontrols.core.EventId
import com.fraudcontrols.core.FeatureSnapshot
import com.fraudcontrols.core.FeatureValue

class RuleEvaluator {
    fun evaluate(
        snapshot: FeatureSnapshot,
        rules: List<RuleDefinition>,
    ): RuleEvaluationResult {
        val evaluations = mutableListOf<RuleEvaluationDetail>()

        for (rule in rules) {
            val featureValues = rule.condition.auditFeatureValues(snapshot)
            if (rule.effectiveMode == RuleMode.DISABLED) {
                evaluations += rule.toEvaluationDetail(
                    conditionResult = RuleEvaluationConditionResult.DISABLED,
                    skippedReason = "rule is disabled",
                    featureValues = featureValues,
                )
                continue
            }

            when (val conditionResult = evaluateCondition(rule.condition, snapshot)) {
                ConditionResult.Matched -> evaluations += rule.toEvaluationDetail(
                    conditionResult = RuleEvaluationConditionResult.MATCHED,
                    skippedReason = null,
                    featureValues = featureValues,
                )
                ConditionResult.NotMatched -> evaluations += rule.toEvaluationDetail(
                    conditionResult = RuleEvaluationConditionResult.NOT_MATCHED,
                    skippedReason = null,
                    featureValues = featureValues,
                )
                is ConditionResult.Unavailable -> evaluations += rule.toEvaluationDetail(
                    conditionResult = RuleEvaluationConditionResult.UNAVAILABLE,
                    skippedReason = conditionResult.reason,
                    featureValues = featureValues,
                )
            }
        }

        val matches = evaluations.mapNotNull { it.toRuleMatch() }
        return RuleEvaluationResult(
            eventId = snapshot.eventId,
            evaluations = evaluations,
            resolvedAction = ActionResolver().resolve(matches),
        )
    }

    private fun evaluateCondition(
        condition: RuleCondition,
        snapshot: FeatureSnapshot,
    ): ConditionResult = when (condition) {
        is RuleCondition.All -> evaluateAll(condition.conditions, snapshot)
        is RuleCondition.Any -> evaluateAny(condition.conditions, snapshot)
        is RuleCondition.Comparison -> evaluateComparison(condition, snapshot)
        is RuleCondition.Not -> evaluateNot(condition.condition, snapshot)
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

    private fun evaluateAny(
        conditions: List<RuleCondition>,
        snapshot: FeatureSnapshot,
    ): ConditionResult {
        val unavailableReasons = mutableListOf<String>()

        for (condition in conditions) {
            when (val result = evaluateCondition(condition, snapshot)) {
                ConditionResult.Matched -> return ConditionResult.Matched
                ConditionResult.NotMatched -> Unit
                is ConditionResult.Unavailable -> unavailableReasons += result.reason
            }
        }

        return if (unavailableReasons.isEmpty()) {
            ConditionResult.NotMatched
        } else {
            ConditionResult.Unavailable(unavailableReasons.joinToString("; "))
        }
    }

    private fun evaluateNot(
        condition: RuleCondition,
        snapshot: FeatureSnapshot,
    ): ConditionResult = when (val result = evaluateCondition(condition, snapshot)) {
        ConditionResult.Matched -> ConditionResult.NotMatched
        ConditionResult.NotMatched -> ConditionResult.Matched
        is ConditionResult.Unavailable -> result
    }

    private fun evaluateComparison(
        condition: RuleCondition.Comparison,
        snapshot: FeatureSnapshot,
    ): ConditionResult {
        val featureValue = snapshot.values[condition.featureName]
            ?: return ConditionResult.Unavailable("missing feature: ${condition.featureName}")

        return when (featureValue) {
            is FeatureValue.Missing -> ConditionResult.Unavailable(
                "feature ${condition.featureName} missing: ${featureValue.reason}",
            )
            is FeatureValue.Unavailable -> ConditionResult.Unavailable(
                "feature ${condition.featureName} unavailable: ${featureValue.reason}",
            )
            else -> compareAvailableValue(
                featureName = condition.featureName,
                featureValue = featureValue,
                operator = condition.operator,
                expected = condition.value,
            )
        }
    }

    private fun compareAvailableValue(
        featureName: String,
        featureValue: FeatureValue,
        operator: ComparisonOperator,
        expected: RuleValue,
    ): ConditionResult {
        val matchResult = when (operator) {
            ComparisonOperator.EQ -> MatchResult.Available(valuesEqual(featureValue, expected))
            ComparisonOperator.NEQ -> MatchResult.Available(!valuesEqual(featureValue, expected))
            ComparisonOperator.LT -> compareNumbers(featureName, featureValue, expected) { actual, threshold -> actual < threshold }
            ComparisonOperator.LTE -> compareNumbers(featureName, featureValue, expected) { actual, threshold -> actual <= threshold }
            ComparisonOperator.GT -> compareNumbers(featureName, featureValue, expected) { actual, threshold -> actual > threshold }
            ComparisonOperator.GTE -> compareNumbers(featureName, featureValue, expected) { actual, threshold -> actual >= threshold }
            ComparisonOperator.IN -> inSet(featureName, featureValue, expected)
            ComparisonOperator.NOT_IN -> when (val result = inSet(featureName, featureValue, expected)) {
                is MatchResult.Available -> MatchResult.Available(!result.matched)
                is MatchResult.Invalid -> result
            }
        }

        return when (matchResult) {
            is MatchResult.Available -> if (matchResult.matched) ConditionResult.Matched else ConditionResult.NotMatched
            is MatchResult.Invalid -> ConditionResult.Unavailable(matchResult.reason)
        }
    }

    private fun valuesEqual(
        featureValue: FeatureValue,
        expected: RuleValue,
    ): Boolean = when (featureValue) {
        is FeatureValue.BooleanValue -> expected == RuleValue.BooleanValue(featureValue.value)
        is FeatureValue.NumberValue -> expected == RuleValue.NumberValue(featureValue.value)
        is FeatureValue.ScoreValue -> expected == RuleValue.NumberValue(featureValue.value)
        is FeatureValue.SetValue -> expected == RuleValue.SetValue(featureValue.values.map { RuleValue.TextValue(it) }.toSet())
        is FeatureValue.TextValue -> expected == RuleValue.TextValue(featureValue.value)
        is FeatureValue.Missing,
        is FeatureValue.Unavailable,
        -> false
    }

    private fun compareNumbers(
        featureName: String,
        featureValue: FeatureValue,
        expected: RuleValue,
        predicate: (Double, Double) -> Boolean,
    ): MatchResult {
        val actual = featureValue.numericValue()
        return when {
            actual == null -> MatchResult.Invalid(
                "feature $featureName expected numeric for comparison but was ${featureValue.typeName()}",
            )
            expected !is RuleValue.NumberValue -> MatchResult.Invalid(
                "rule value for $featureName expected numeric for comparison but was ${expected.typeName()}",
            )
            else -> MatchResult.Available(predicate(actual, expected.value))
        }
    }

    private fun inSet(
        featureName: String,
        featureValue: FeatureValue,
        expected: RuleValue,
    ): MatchResult = if (expected !is RuleValue.SetValue) {
        MatchResult.Invalid("rule value for $featureName expected set for membership but was ${expected.typeName()}")
    } else {
        MatchResult.Available(
            when (featureValue) {
                is FeatureValue.BooleanValue -> expected.values.contains(RuleValue.BooleanValue(featureValue.value))
                is FeatureValue.NumberValue -> expected.values.contains(RuleValue.NumberValue(featureValue.value))
                is FeatureValue.ScoreValue -> expected.values.contains(RuleValue.NumberValue(featureValue.value))
                is FeatureValue.SetValue -> featureValue.values.any { expected.values.contains(RuleValue.TextValue(it)) }
                is FeatureValue.TextValue -> expected.values.contains(RuleValue.TextValue(featureValue.value))
                is FeatureValue.Missing,
                is FeatureValue.Unavailable,
                -> false
            },
        )
    }
}

class ActionResolver {
    fun resolve(matches: List<RuleMatch>): ResolvedRuleAction? = resolutionCandidates(matches).firstOrNull()

    fun resolutionCandidates(matches: List<RuleMatch>): List<ResolvedRuleAction> = matches
        .asSequence()
        .filter { it.mode == RuleMode.ENFORCE }
        .mapNotNull { match ->
            match.action.decisionAction()?.let { decisionAction ->
                ResolvedRuleAction(
                    ruleId = match.ruleId,
                    ruleVersion = match.ruleVersion,
                    decisionAction = decisionAction,
                    action = match.action,
                    priority = match.priority,
                )
            }
        }
        .sortedWith(
            compareByDescending<ResolvedRuleAction> { it.priority }
                .thenByDescending { it.decisionAction.severity() }
                .thenBy { it.ruleId },
        )
        .toList()
}

data class RuleEvaluationResult(
    val eventId: EventId,
    val evaluations: List<RuleEvaluationDetail>,
    val resolvedAction: ResolvedRuleAction?,
) {
    val matches: List<RuleMatch> = evaluations.mapNotNull { it.toRuleMatch() }
    val skipped: List<SkippedRule> = evaluations.mapNotNull { it.toSkippedRule() }
    val resolutionCandidates: List<ResolvedRuleAction> = ActionResolver().resolutionCandidates(matches)
}

data class RuleEvaluationDetail(
    val ruleId: String,
    val ruleVersion: Int,
    val mode: RuleMode,
    val priority: Int,
    val conditionResult: RuleEvaluationConditionResult,
    val action: RuleAction,
    val featureValues: Map<String, FeatureValue>,
    val skippedReason: String? = null,
) {
    init {
        require(ruleId.isNotBlank()) { "evaluated rule id must not be blank" }
        require(ruleVersion > 0) { "evaluated rule version must be positive" }
        require(featureValues.keys.none { it.isBlank() }) { "evaluated feature names must not be blank" }
        if (conditionResult.isSkipped) {
            require(!skippedReason.isNullOrBlank()) { "skipped rule reason must not be blank" }
        } else {
            require(skippedReason == null) { "non-skipped rule must not include a skipped reason" }
        }
    }

    fun toRuleMatch(): RuleMatch? = if (conditionResult == RuleEvaluationConditionResult.MATCHED) {
        RuleMatch(
            ruleId = ruleId,
            ruleVersion = ruleVersion,
            mode = mode,
            priority = priority,
            action = action,
        )
    } else {
        null
    }

    fun toSkippedRule(): SkippedRule? = if (conditionResult.isSkipped) {
        SkippedRule(
            ruleId = ruleId,
            ruleVersion = ruleVersion,
            reason = skippedReason ?: error("skipped reason invariant violated"),
        )
    } else {
        null
    }
}

enum class RuleEvaluationConditionResult {
    MATCHED,
    NOT_MATCHED,
    UNAVAILABLE,
    DISABLED,
    ;

    val isSkipped: Boolean
        get() = this == UNAVAILABLE || this == DISABLED
}

data class RuleMatch(
    val ruleId: String,
    val ruleVersion: Int,
    val mode: RuleMode,
    val priority: Int,
    val action: RuleAction,
)

data class SkippedRule(
    val ruleId: String,
    val ruleVersion: Int,
    val reason: String,
) {
    init {
        require(reason.isNotBlank()) { "skipped rule reason must not be blank" }
    }
}

data class ResolvedRuleAction(
    val ruleId: String,
    val ruleVersion: Int,
    val decisionAction: DecisionAction,
    val action: RuleAction,
    val priority: Int,
)

private sealed interface ConditionResult {
    data object Matched : ConditionResult
    data object NotMatched : ConditionResult
    data class Unavailable(val reason: String) : ConditionResult
}

private sealed interface MatchResult {
    data class Available(val matched: Boolean) : MatchResult
    data class Invalid(val reason: String) : MatchResult
}

private fun RuleDefinition.toEvaluationDetail(
    conditionResult: RuleEvaluationConditionResult,
    skippedReason: String?,
    featureValues: Map<String, FeatureValue>,
): RuleEvaluationDetail = RuleEvaluationDetail(
    ruleId = id,
    ruleVersion = version,
    mode = effectiveMode,
    priority = priority,
    conditionResult = conditionResult,
    action = action,
    featureValues = featureValues,
    skippedReason = skippedReason,
)

private fun RuleCondition.auditFeatureValues(snapshot: FeatureSnapshot): Map<String, FeatureValue> = featureNames().associateWith { featureName ->
    snapshot.values[featureName] ?: FeatureValue.Missing("feature not present in snapshot")
}

private fun DecisionAction.severity(): Int = when (this) {
    DecisionAction.ALLOW -> 0
    DecisionAction.CHALLENGE -> 1
    DecisionAction.HOLD -> 2
    DecisionAction.DENY -> 3
}

private fun FeatureValue.typeName(): String = when (this) {
    is FeatureValue.BooleanValue -> "boolean"
    is FeatureValue.Missing -> "missing"
    is FeatureValue.NumberValue -> "numeric"
    is FeatureValue.ScoreValue -> "numeric"
    is FeatureValue.SetValue -> "set"
    is FeatureValue.TextValue -> "text"
    is FeatureValue.Unavailable -> "unavailable"
}

private fun FeatureValue.numericValue(): Double? = when (this) {
    is FeatureValue.NumberValue -> value
    is FeatureValue.ScoreValue -> value
    is FeatureValue.BooleanValue,
    is FeatureValue.Missing,
    is FeatureValue.SetValue,
    is FeatureValue.TextValue,
    is FeatureValue.Unavailable,
    -> null
}

private fun RuleValue.typeName(): String = when (this) {
    is RuleValue.BooleanValue -> "boolean"
    is RuleValue.NumberValue -> "numeric"
    is RuleValue.SetValue -> "set"
    is RuleValue.TextValue -> "text"
}
