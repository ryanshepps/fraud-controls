package com.fraudcontrols.decisioning

import com.fraudcontrols.core.Decision
import com.fraudcontrols.rules.RuleEvaluationResult

interface DecisioningMetrics {
    fun recordDecision(decision: Decision)

    fun recordDecisionLatency(latencyMs: Double)

    fun recordDecisionSideEffectFailure(sideEffect: DecisionSideEffect)

    fun recordFeatureResolutionLatency(latencyMs: Double)

    fun recordRuleEvaluationLatency(latencyMs: Double)

    fun recordRuleEvaluation(evaluation: RuleEvaluationResult)
}

data object NoopDecisioningMetrics : DecisioningMetrics {
    override fun recordDecision(decision: Decision) = Unit

    override fun recordDecisionLatency(latencyMs: Double) = Unit

    override fun recordDecisionSideEffectFailure(sideEffect: DecisionSideEffect) = Unit

    override fun recordFeatureResolutionLatency(latencyMs: Double) = Unit

    override fun recordRuleEvaluationLatency(latencyMs: Double) = Unit

    override fun recordRuleEvaluation(evaluation: RuleEvaluationResult) = Unit
}

enum class DecisionSideEffect {
    AUDIT_RECORD,
    RULE_EVALUATION_PUBLISH,
    DECISION_PUBLISH,
}
