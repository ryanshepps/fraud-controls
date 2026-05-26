package com.fraudcontrols.scoring

import com.fraudcontrols.core.FeatureValue
import com.fraudcontrols.core.ScoreResult
import com.fraudcontrols.core.ScoringContext
import com.fraudcontrols.features.FeatureResolver

/**
 * Scores an event with hand-authored feature weights.
 *
 * [RuleBasedScorer] is the configurable, non-model scorer in the scoring module. It resolves each
 * feature named by [RuleBasedScorerConfig], multiplies the feature value by its configured weight,
 * adds those contributions to the configured intercept, and converts the raw score into a
 * probability with the module sigmoid.
 *
 * This scorer is useful as a simple baseline or as the fallback behind [FailoverScorer]. Its weights
 * are risk policy, not learned model output. They should be changed through review and validation
 * because they directly affect false positives and false negatives.
 */
class RuleBasedScorer(
    override val name: String,
    override val version: String,
    private val featureResolver: FeatureResolver,
    private val config: RuleBasedScorerConfig,
) : Scorer {
    init {
        require(name.isNotBlank()) { "scorer name must not be blank" }
        require(version.isNotBlank()) { "scorer version must not be blank" }
    }

    override suspend fun score(context: ScoringContext): ScoreResult {
        val startNanos = System.nanoTime()
        val request = featureResolver.request(context)
        var rawScore = config.intercept
        val factors = mutableListOf<com.fraudcontrols.core.Factor>()

        for (weight in config.weights) {
            val featureValue = request.resolve(weight.featureName)
            val modelValue = featureValue.numericValueOr(weight.missingValue)
            val contribution = modelValue * weight.weight
            rawScore += contribution
            factors += factor(name = weight.featureName, contribution = contribution)
        }

        return ScoreResult(
            score = sigmoid(rawScore),
            rawScore = rawScore,
            contributingFactors = factors,
            modelVersion = version,
            latencyMs = elapsedMs(startNanos),
        )
    }

    private fun FeatureValue.numericValueOr(missingValue: Double): Double =
        when (this) {
            is FeatureValue.BooleanValue -> if (value) 1.0 else 0.0
            is FeatureValue.NumberValue -> value
            is FeatureValue.Missing -> missingValue
            is FeatureValue.Unavailable -> missingValue
            is FeatureValue.SetValue -> missingValue
            is FeatureValue.TextValue -> missingValue
        }
}
