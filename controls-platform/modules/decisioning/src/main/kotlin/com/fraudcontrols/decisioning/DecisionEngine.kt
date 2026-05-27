package com.fraudcontrols.decisioning

import com.fraudcontrols.core.Decision
import com.fraudcontrols.core.DecisionAction
import com.fraudcontrols.core.EventId
import com.fraudcontrols.core.FeatureSnapshot
import com.fraudcontrols.core.FeatureValue
import com.fraudcontrols.core.ReasonCode
import com.fraudcontrols.core.ScoreResult
import com.fraudcontrols.core.ScoringContext
import com.fraudcontrols.core.TransactionEvent
import com.fraudcontrols.decisioning.contracts.DecisionAuditRowContract
import com.fraudcontrols.features.FeatureResolver
import com.fraudcontrols.features.FraudFeatureNames
import com.fraudcontrols.rules.ResolvedRuleAction
import com.fraudcontrols.rules.RuleCondition
import com.fraudcontrols.rules.RuleDefinition
import com.fraudcontrols.rules.RuleEvaluationResult
import com.fraudcontrols.rules.RuleEvaluator
import java.time.Instant

class DecisionEngine(
    private val featureResolver: FeatureResolver,
    private val ruleEvaluator: RuleEvaluator = RuleEvaluator(),
    private val metrics: DecisioningMetrics = NoopDecisioningMetrics,
) {
    suspend fun decide(
        event: TransactionEvent,
        rules: List<RuleDefinition>,
        decidedAt: Instant,
    ): DecisioningResult {
        val context = ScoringContext(event)
        val features = timed {
            featureResolver.resolveRuleFeatures(
                context = context,
                featureNames = rules.flatMap { it.condition.featureNames() }.toSet(),
            )
        }.also { metrics.recordFeatureResolutionLatency(it.elapsedMs) }.value
        val score = features.scoreResult()
        val ruleEvaluation = timed {
            ruleEvaluator.evaluate(features, rules)
        }.also {
            metrics.recordRuleEvaluationLatency(it.elapsedMs)
            metrics.recordRuleEvaluation(it.value)
        }.value
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
    private val metrics: DecisioningMetrics = NoopDecisioningMetrics,
) {
    suspend fun process(
        event: TransactionEvent,
        rules: List<RuleDefinition>,
        decidedAt: Instant,
    ): DecisioningResult {
        val result = timed {
            val decisioningResult = engine.decide(event = event, rules = rules, decidedAt = decidedAt)
            auditSink.record(decisioningResult.record)
            ruleEvaluationPublisher.publish(decisioningResult.ruleEvaluation)
            decisionPublisher.publish(decisioningResult.decision)
            decisioningResult
        }
        metrics.recordDecision(result.value.decision)
        metrics.recordDecisionLatency(result.elapsedMs)
        return result.value
    }
}

interface DecisionAuditSink {
    suspend fun record(record: DecisionRecord)
}

interface DecisionRecordReader {
    suspend fun find(eventId: EventId): DecisionAuditRowContract?
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
): FeatureSnapshot {
    val request = request(context)
    val values = linkedMapOf<String, FeatureValue>()

    for (featureName in featureNames + FraudFeatureNames.FRAUD_MODEL_SCORE) {
        values[featureName] = request.resolve(featureName)
    }

    return FeatureSnapshot(eventId = context.eventId, values = values)
}

private data class Timed<T>(
    val value: T,
    val elapsedMs: Double,
)

private suspend fun <T> timed(block: suspend () -> T): Timed<T> {
    val startedAt = System.nanoTime()
    val value = block()
    return Timed(
        value = value,
        elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0,
    )
}

private fun FeatureSnapshot.scoreResult(): ScoreResult =
    when (val value = values[FraudFeatureNames.FRAUD_MODEL_SCORE]) {
        is FeatureValue.ScoreValue -> value.result
        is FeatureValue.Missing -> error("${FraudFeatureNames.FRAUD_MODEL_SCORE} missing: ${value.reason}")
        is FeatureValue.Unavailable -> error("${FraudFeatureNames.FRAUD_MODEL_SCORE} unavailable: ${value.reason}")
        null -> error("${FraudFeatureNames.FRAUD_MODEL_SCORE} was not resolved")
        else -> error("${FraudFeatureNames.FRAUD_MODEL_SCORE} must be provided by ScorerFeatureProvider")
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
