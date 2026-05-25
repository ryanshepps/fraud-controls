package com.fraudcontrols.decisioning

import com.fraudcontrols.core.Decision
import com.fraudcontrols.core.DecisionAction
import com.fraudcontrols.core.FeatureSnapshot
import com.fraudcontrols.core.FeatureValue
import com.fraudcontrols.core.ReasonCode
import com.fraudcontrols.core.ScoreResult
import com.fraudcontrols.core.ScoringContext
import com.fraudcontrols.core.TransactionEvent
import com.fraudcontrols.features.FeatureResolver
import com.fraudcontrols.features.FraudFeatureNames
import com.fraudcontrols.rules.ResolvedRuleAction
import com.fraudcontrols.rules.RuleCondition
import com.fraudcontrols.rules.RuleDefinition
import com.fraudcontrols.rules.RuleEvaluationResult
import com.fraudcontrols.rules.RuleEvaluator
import com.fraudcontrols.scoring.Scorer
import java.time.Instant

class DecisionEngine(
    private val featureResolver: FeatureResolver,
    private val scorer: Scorer,
    private val ruleEvaluator: RuleEvaluator = RuleEvaluator(),
) {
    suspend fun decide(
        event: TransactionEvent,
        rules: List<RuleDefinition>,
        decidedAt: Instant,
    ): DecisioningResult {
        val context = ScoringContext(event)
        val score = scorer.score(context)
        val features = featureResolver.resolveRuleFeatures(
            context = context,
            featureNames = rules.flatMap { it.condition.featureNames() }.toSet(),
            score = score,
        )
        val ruleEvaluation = ruleEvaluator.evaluate(features, rules)
        val resolvedAction = ruleEvaluation.resolvedAction

        val decision = Decision(
            eventId = event.eventId,
            action = resolvedAction?.decisionAction ?: score.fallbackAction(),
            reasonCodes = resolvedAction?.reasonCodes() ?: listOf(score.fallbackReasonCode()),
            score = score,
            ruleEvaluationIds = ruleEvaluation.matches.map { it.ruleId },
            decidedAt = decidedAt,
        )

        return DecisioningResult(
            decision = decision,
            features = features,
            ruleEvaluation = ruleEvaluation,
            record = DecisionRecord(
                decision = decision,
                features = features,
                ruleEvaluation = ruleEvaluation,
                score = score,
            ),
        )
    }
}

class DecisionProcessor(
    private val engine: DecisionEngine,
    private val auditSink: DecisionAuditSink = NoopDecisionAuditSink,
    private val decisionPublisher: DecisionPublisher = NoopDecisionPublisher,
    private val ruleEvaluationPublisher: RuleEvaluationPublisher = NoopRuleEvaluationPublisher,
) {
    suspend fun process(
        event: TransactionEvent,
        rules: List<RuleDefinition>,
        decidedAt: Instant,
    ): DecisioningResult {
        val result = engine.decide(event = event, rules = rules, decidedAt = decidedAt)
        auditSink.record(result.record)
        ruleEvaluationPublisher.publish(result.ruleEvaluation)
        decisionPublisher.publish(result.decision)
        return result
    }
}

interface DecisionAuditSink {
    suspend fun record(record: DecisionRecord)
}

interface DecisionPublisher {
    suspend fun publish(decision: Decision)
}

interface RuleEvaluationPublisher {
    suspend fun publish(evaluation: RuleEvaluationResult)
}

data class DecisioningResult(
    val decision: Decision,
    val features: FeatureSnapshot,
    val ruleEvaluation: RuleEvaluationResult,
    val record: DecisionRecord,
)

data class DecisionRecord(
    val decision: Decision,
    val features: FeatureSnapshot,
    val ruleEvaluation: RuleEvaluationResult,
    val score: ScoreResult,
)

object NoopDecisionAuditSink : DecisionAuditSink {
    override suspend fun record(record: DecisionRecord) = Unit
}

object NoopDecisionPublisher : DecisionPublisher {
    override suspend fun publish(decision: Decision) = Unit
}

object NoopRuleEvaluationPublisher : RuleEvaluationPublisher {
    override suspend fun publish(evaluation: RuleEvaluationResult) = Unit
}

private fun RuleCondition.featureNames(): Set<String> =
    when (this) {
        is RuleCondition.All -> conditions.flatMap { it.featureNames() }.toSet()
        is RuleCondition.Any -> conditions.flatMap { it.featureNames() }.toSet()
        is RuleCondition.Comparison -> setOf(featureName)
        is RuleCondition.Not -> condition.featureNames()
    }

private suspend fun FeatureResolver.resolveRuleFeatures(
    context: ScoringContext,
    featureNames: Set<String>,
    score: ScoreResult,
): FeatureSnapshot {
    val request = request(context)
    val values = linkedMapOf<String, FeatureValue>()

    for (featureName in featureNames) {
        values[featureName] = if (featureName == FraudFeatureNames.FRAUD_MODEL_SCORE) {
            FeatureValue.NumberValue(score.score)
        } else {
            request.resolve(featureName)
        }
    }

    return FeatureSnapshot(eventId = context.eventId, values = values)
}

private fun ScoreResult.fallbackAction(): DecisionAction =
    when {
        score >= HIGH_SCORE_THRESHOLD -> DecisionAction.HOLD
        score >= MEDIUM_SCORE_THRESHOLD -> DecisionAction.CHALLENGE
        else -> DecisionAction.ALLOW
    }

private fun ScoreResult.fallbackReasonCode(): ReasonCode =
    when {
        score >= HIGH_SCORE_THRESHOLD -> ReasonCode("score_high")
        score >= MEDIUM_SCORE_THRESHOLD -> ReasonCode("score_medium")
        else -> ReasonCode("score_low")
    }

private fun ResolvedRuleAction.reasonCodes(): List<ReasonCode> =
    listOf(action.reasonCode ?: ReasonCode("rule_$ruleId"))

private const val MEDIUM_SCORE_THRESHOLD = 0.35
private const val HIGH_SCORE_THRESHOLD = 0.70
