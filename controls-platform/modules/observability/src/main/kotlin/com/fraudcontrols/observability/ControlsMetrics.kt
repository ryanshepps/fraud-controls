package com.fraudcontrols.observability

import com.fraudcontrols.core.DecisionAction
import com.fraudcontrols.decisioning.DecisionSideEffect
import com.fraudcontrols.rules.RuleActionType
import com.fraudcontrols.rules.RuleMode

interface ControlsMetrics {
    fun recordDecision(action: DecisionAction)

    fun recordDecisionLatency(latencyMs: Double)

    fun recordDecisionSideEffectFailure(sideEffect: DecisionSideEffect)

    fun recordFeatureResolutionLatency(latencyMs: Double)

    fun recordScoringLatency(
        scorerName: String,
        scorerVersion: String,
        degraded: Boolean,
        latencyMs: Double,
    )

    fun recordRuleEvaluationLatency(latencyMs: Double)

    fun recordRuleMatch(
        ruleId: String,
        mode: RuleMode,
        actionType: RuleActionType,
    )

    fun updateShadowRuleMetrics(
        ruleId: String,
        fireRate: Double,
        wouldHaveBlockedRate: Double,
        agreementRate: Double,
    )

    fun updateScorerPairMetrics(
        primaryScorer: String,
        shadowScorer: String,
        scoreDivergence: Double,
        decisionFlipRate: Double,
    )
}

data object NoopControlsMetrics : ControlsMetrics {
    override fun recordDecision(action: DecisionAction) = Unit

    override fun recordDecisionLatency(latencyMs: Double) = Unit

    override fun recordDecisionSideEffectFailure(sideEffect: DecisionSideEffect) = Unit

    override fun recordFeatureResolutionLatency(latencyMs: Double) = Unit

    override fun recordScoringLatency(
        scorerName: String,
        scorerVersion: String,
        degraded: Boolean,
        latencyMs: Double,
    ) = Unit

    override fun recordRuleEvaluationLatency(latencyMs: Double) = Unit

    override fun recordRuleMatch(
        ruleId: String,
        mode: RuleMode,
        actionType: RuleActionType,
    ) = Unit

    override fun updateShadowRuleMetrics(
        ruleId: String,
        fireRate: Double,
        wouldHaveBlockedRate: Double,
        agreementRate: Double,
    ) = Unit

    override fun updateScorerPairMetrics(
        primaryScorer: String,
        shadowScorer: String,
        scoreDivergence: Double,
        decisionFlipRate: Double,
    ) = Unit
}
