package com.fraudcontrols.scoring

import com.fraudcontrols.core.Factor
import com.fraudcontrols.core.FeatureValue
import com.fraudcontrols.core.ScoreResult
import com.fraudcontrols.core.ScoringContext
import com.fraudcontrols.features.FeatureProvider
import com.fraudcontrols.features.FraudFeatureNames
import kotlin.math.exp

interface Scorer {
    val name: String
    val version: String
    suspend fun score(context: ScoringContext): ScoreResult
}

class ScorerFeatureProvider(
    private val scorer: Scorer,
) : FeatureProvider {
    override val featureName: String = FraudFeatureNames.FRAUD_MODEL_SCORE

    override suspend fun compute(context: ScoringContext) =
        FeatureValue.ScoreValue(scorer.score(context))
}

data class RuleBasedScorerConfig(
    val intercept: Double,
    val weights: List<FeatureWeight>,
) {
    init {
        require(intercept.isFinite()) { "rule-based scorer intercept must be finite" }
        require(weights.isNotEmpty()) { "rule-based scorer must include at least one weight" }
        require(weights.map { it.featureName }.toSet().size == weights.size) {
            "rule-based scorer weights must have unique feature names"
        }
    }
}

data class FeatureWeight(
    val featureName: String,
    val weight: Double,
    val missingValue: Double = 0.0,
) {
    init {
        require(featureName.isNotBlank()) { "feature name must not be blank" }
        require(weight.isFinite()) { "feature weight must be finite" }
        require(missingValue.isFinite()) { "missing feature value must be finite" }
    }
}

data class PlattCalibrator(
    val slope: Double = 1.0,
    val intercept: Double = 0.0,
) {
    init {
        require(slope.isFinite()) { "calibrator slope must be finite" }
        require(intercept.isFinite()) { "calibrator intercept must be finite" }
    }

    fun calibrate(rawScore: Double): Double =
        sigmoid(slope * rawScore + intercept)
}

internal fun sigmoid(rawScore: Double): Double {
    require(rawScore.isFinite()) { "raw score must be finite" }
    return when {
        rawScore >= 35.0 -> 1.0
        rawScore <= -35.0 -> 0.0
        else -> 1.0 / (1.0 + exp(-rawScore))
    }
}

internal fun elapsedMs(startNanos: Long): Double =
    (System.nanoTime() - startNanos) / 1_000_000.0

internal fun factor(
    name: String,
    contribution: Double,
): Factor =
    Factor(name = name, contribution = contribution)
