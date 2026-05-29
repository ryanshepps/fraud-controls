package com.fraudcontrols.observability

import com.fraudcontrols.core.Decision
import com.fraudcontrols.decisioning.DecisionSideEffect
import com.fraudcontrols.decisioning.DecisioningMetrics
import com.fraudcontrols.rules.RuleEvaluationResult

class DecisioningMetricsAdapter(
    private val metrics: ControlsMetrics,
) : DecisioningMetrics {
    override fun recordDecision(decision: Decision) {
        metrics.recordDecision(decision.action)
    }

    override fun recordDecisionLatency(latencyMs: Double) {
        metrics.recordDecisionLatency(latencyMs)
    }

    override fun recordDecisionSideEffectFailure(sideEffect: DecisionSideEffect) {
        metrics.recordDecisionSideEffectFailure(sideEffect)
    }

    override fun recordFeatureResolutionLatency(latencyMs: Double) {
        metrics.recordFeatureResolutionLatency(latencyMs)
    }

    override fun recordRuleEvaluationLatency(latencyMs: Double) {
        metrics.recordRuleEvaluationLatency(latencyMs)
    }

    override fun recordRuleEvaluation(evaluation: RuleEvaluationResult) {
        for (match in evaluation.matches) {
            metrics.recordRuleMatch(
                ruleId = match.ruleId,
                mode = match.mode,
                actionType = match.action.type,
            )
        }
    }
}
