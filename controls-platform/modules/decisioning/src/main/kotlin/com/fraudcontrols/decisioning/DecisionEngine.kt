package com.fraudcontrols.decisioning

import com.fraudcontrols.core.Decision
import com.fraudcontrols.core.DecisionAction
import com.fraudcontrols.core.FeatureSnapshot
import com.fraudcontrols.core.ReasonCode
import com.fraudcontrols.core.RiskBand
import com.fraudcontrols.core.ScoreResult
import com.fraudcontrols.core.TransactionEvent
import com.fraudcontrols.features.FraudFeatureExtractor
import com.fraudcontrols.rules.RuleDefinition
import com.fraudcontrols.rules.RuleEvaluationResult
import com.fraudcontrols.rules.RuleEvaluator
import com.fraudcontrols.scoring.BaselineRiskScorer
import java.time.Instant

class DecisionEngine(
    private val featureExtractor: FraudFeatureExtractor = FraudFeatureExtractor(),
    private val riskScorer: BaselineRiskScorer = BaselineRiskScorer(),
    private val ruleEvaluator: RuleEvaluator = RuleEvaluator(),
) {
    fun decide(
        event: TransactionEvent,
        rules: List<RuleDefinition>,
        decidedAt: Instant,
    ): DecisioningResult {
        val features = featureExtractor.extract(event)
        return decide(
            event = event,
            rules = rules,
            score = riskScorer.score(features),
            features = features,
            decidedAt = decidedAt,
        )
    }

    fun decide(
        event: TransactionEvent,
        rules: List<RuleDefinition>,
        score: ScoreResult,
        decidedAt: Instant,
    ): DecisioningResult {
        val features = featureExtractor.extract(event)
        return decide(
            event = event,
            rules = rules,
            score = score,
            features = features,
            decidedAt = decidedAt,
        )
    }

    private fun decide(
        event: TransactionEvent,
        rules: List<RuleDefinition>,
        score: ScoreResult,
        features: FeatureSnapshot,
        decidedAt: Instant,
    ): DecisioningResult {
        val ruleEvaluation = ruleEvaluator.evaluate(features, rules)
        val matchedRules = ruleEvaluation.matches
        val action = matchedRules.maxByOrNull { it.action.severity() }?.action ?: score.band.fallbackAction()
        val reasonCodes = matchedRules.map { it.reasonCode }.ifEmpty { listOf(score.band.fallbackReasonCode()) }

        return DecisioningResult(
            decision = Decision(
                eventId = event.eventId,
                action = action,
                reasonCodes = reasonCodes,
                score = score,
                ruleEvaluationIds = matchedRules.map { it.ruleId },
                decidedAt = decidedAt,
            ),
            features = features,
            ruleEvaluation = ruleEvaluation,
        )
    }
}

data class DecisioningResult(
    val decision: Decision,
    val features: FeatureSnapshot,
    val ruleEvaluation: RuleEvaluationResult,
)

private fun DecisionAction.severity(): Int =
    when (this) {
        DecisionAction.ALLOW -> 0
        DecisionAction.CHALLENGE -> 1
        DecisionAction.HOLD -> 2
        DecisionAction.DENY -> 3
    }

private fun RiskBand.fallbackAction(): DecisionAction =
    when (this) {
        RiskBand.LOW -> DecisionAction.ALLOW
        RiskBand.MEDIUM -> DecisionAction.CHALLENGE
        RiskBand.HIGH -> DecisionAction.HOLD
    }

private fun RiskBand.fallbackReasonCode(): ReasonCode =
    when (this) {
        RiskBand.LOW -> ReasonCode("score_low")
        RiskBand.MEDIUM -> ReasonCode("score_medium")
        RiskBand.HIGH -> ReasonCode("score_high")
    }
