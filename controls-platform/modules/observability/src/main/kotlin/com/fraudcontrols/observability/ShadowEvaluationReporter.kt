package com.fraudcontrols.observability

import com.fraudcontrols.core.DecisionAction
import com.fraudcontrols.decisioning.contracts.parseRuleEvaluationEventContract
import com.fraudcontrols.rules.RuleActionType
import com.fraudcontrols.rules.RuleEvaluationResult
import com.fraudcontrols.rules.RuleMode
import com.fraudcontrols.rules.decisionAction
import com.fraudcontrols.scoring.ShadowEvaluation
import com.fraudcontrols.scoring.ShadowScorerRole
import kotlin.math.abs
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ShadowEvaluationReporter(
    private val metrics: ControlsMetrics,
    private val threshold: Double = 0.5,
    private val windowSize: Int = 1_000,
) {
    init {
        require(threshold in 0.0..1.0) { "threshold must be in [0.0, 1.0]" }
        require(windowSize > 0) { "window size must be positive" }
    }

    fun recordShadowEvaluationPayload(payload: String) {
        recordShadowEvaluations(parseShadowEvaluationEventContract(payload))
    }

    fun recordRuleEvaluationPayload(payload: String) {
        recordRuleEvaluation(parseRuleEvaluationEventContract(payload).toRuleEvaluationObservation())
    }

    fun recordShadowEvaluations(evaluations: List<ShadowEvaluation>) {
        val primary = evaluations.firstOrNull { it.role == ShadowScorerRole.PRIMARY && it.result != null }
            ?: return
        val primaryScore = primary.result?.score ?: return

        for (shadow in evaluations.filter { it.role == ShadowScorerRole.SHADOW && it.result != null }) {
            val shadowScore = shadow.result?.score ?: continue
            val stats = scorerPairStats.getOrPut(
                ScorerPairKey(primary.scorerName, shadow.scorerName),
            ) {
                ScorerPairStats(windowSize)
            }
            stats.scoreDivergence.add(abs(primaryScore - shadowScore))
            stats.decisionFlips.add(if ((primaryScore >= threshold) != (shadowScore >= threshold)) 1.0 else 0.0)
            metrics.updateScorerPairMetrics(
                primaryScorer = primary.scorerName,
                shadowScorer = shadow.scorerName,
                scoreDivergence = stats.scoreDivergence.average(),
                decisionFlipRate = stats.decisionFlips.average(),
            )
        }
    }

    fun recordRuleEvaluation(evaluation: RuleEvaluationResult) {
        recordRuleEvaluation(evaluation.toRuleEvaluationObservation())
    }

    private fun recordRuleEvaluation(evaluation: RuleEvaluationObservation) {
        val shadowMatches = evaluation.matches.filter { it.mode == RuleMode.SHADOW }
        val matchedRuleIds = shadowMatches.map { it.ruleId }.toSet()

        for ((ruleId, stats) in shadowRuleStats) {
            if (ruleId !in matchedRuleIds) {
                stats.fireRate.add(0.0)
                stats.wouldHaveBlockedRate.add(0.0)
                stats.agreementRate.add(
                    if (evaluation.resolvedAction == null || evaluation.resolvedAction == DecisionAction.ALLOW) {
                        1.0
                    } else {
                        0.0
                    },
                )
                metrics.updateShadowRuleMetrics(
                    ruleId = ruleId,
                    fireRate = stats.fireRate.average(),
                    wouldHaveBlockedRate = stats.wouldHaveBlockedRate.average(),
                    agreementRate = stats.agreementRate.average(),
                )
            }
        }

        for (match in shadowMatches) {
            val stats = shadowRuleStats.getOrPut(match.ruleId) { ShadowRuleStats(windowSize) }
            val wouldHaveBlocked = match.decisionAction in blockingActions
            stats.fireRate.add(1.0)
            stats.wouldHaveBlockedRate.add(if (wouldHaveBlocked) 1.0 else 0.0)
            stats.agreementRate.add(if (match.decisionAction == evaluation.resolvedAction) 1.0 else 0.0)
            metrics.updateShadowRuleMetrics(
                ruleId = match.ruleId,
                fireRate = stats.fireRate.average(),
                wouldHaveBlockedRate = stats.wouldHaveBlockedRate.average(),
                agreementRate = stats.agreementRate.average(),
            )
        }
    }

    private val shadowRuleStats = linkedMapOf<String, ShadowRuleStats>()
    private val scorerPairStats = linkedMapOf<ScorerPairKey, ScorerPairStats>()
}

private data class RuleEvaluationObservation(
    val matches: List<RuleMatchObservation>,
    val resolvedAction: DecisionAction?,
)

private data class RuleMatchObservation(
    val ruleId: String,
    val mode: RuleMode,
    val decisionAction: DecisionAction?,
)

private data class ShadowRuleStats(
    val fireRate: RollingAverage,
    val wouldHaveBlockedRate: RollingAverage,
    val agreementRate: RollingAverage,
) {
    constructor(windowSize: Int) : this(
        fireRate = RollingAverage(windowSize),
        wouldHaveBlockedRate = RollingAverage(windowSize),
        agreementRate = RollingAverage(windowSize),
    )
}

private data class ScorerPairKey(
    val primaryScorer: String,
    val shadowScorer: String,
)

private data class ScorerPairStats(
    val scoreDivergence: RollingAverage,
    val decisionFlips: RollingAverage,
) {
    constructor(windowSize: Int) : this(
        scoreDivergence = RollingAverage(windowSize),
        decisionFlips = RollingAverage(windowSize),
    )
}

private class RollingAverage(
    private val windowSize: Int,
) {
    private val values = ArrayDeque<Double>()
    private var sum = 0.0

    fun add(value: Double) {
        values.addLast(value)
        sum += value
        while (values.size > windowSize) {
            sum -= values.removeFirst()
        }
    }

    fun average(): Double =
        if (values.isEmpty()) 0.0 else sum / values.size
}

private fun RuleEvaluationResult.toRuleEvaluationObservation(): RuleEvaluationObservation =
    RuleEvaluationObservation(
        matches = matches.map {
            RuleMatchObservation(
                ruleId = it.ruleId,
                mode = it.mode,
                decisionAction = it.action.decisionAction(),
            )
        },
        resolvedAction = resolvedAction?.decisionAction,
    )

private fun kotlinx.serialization.json.JsonObject.toRuleEvaluationObservation(): RuleEvaluationObservation =
    RuleEvaluationObservation(
        matches = this["matches"]?.jsonArray.orEmpty().map { element ->
            val match = element.jsonObject
            RuleMatchObservation(
                ruleId = match.requiredString("rule_id"),
                mode = RuleMode.valueOf(match.requiredString("mode")),
                decisionAction = RuleActionType.valueOf(match.requiredString("action_type")).decisionAction(),
            )
        },
        resolvedAction = this["resolved_action"]?.jsonObject
            ?.requiredString("decision_action")
            ?.let(DecisionAction::valueOf),
    )

private fun kotlinx.serialization.json.JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.content ?: error("missing required field: $name")

private fun RuleActionType.decisionAction(): DecisionAction? =
    when (this) {
        RuleActionType.ALLOW -> DecisionAction.ALLOW
        RuleActionType.BLOCK -> DecisionAction.DENY
        RuleActionType.CHALLENGE -> DecisionAction.CHALLENGE
        RuleActionType.REVIEW_QUEUE -> DecisionAction.HOLD
        RuleActionType.TAG -> null
    }

private val blockingActions = setOf(
    DecisionAction.CHALLENGE,
    DecisionAction.HOLD,
    DecisionAction.DENY,
)
