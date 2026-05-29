package com.fraudcontrols.scoring

import com.fraudcontrols.core.ScoreResult
import com.fraudcontrols.core.ScoringContext

/**
 * Client boundary for the model service used by [XGBoostScorer].
 *
 * The scoring module depends on this interface, not on a concrete sidecar transport. Production can
 * provide a network client later, while tests and local configs can use [StubXGBoostScoreClient].
 */
interface XGBoostScoreClient {
    suspend fun score(
        context: ScoringContext,
        modelId: String,
    ): XGBoostScoreResponse
}

/**
 * Raw model output returned by [XGBoostScoreClient].
 *
 * [rawScore] is calibrated by [XGBoostScorer] before it becomes the returned probability.
 * [shapValues] become score factors so downstream audit and explainability paths can see which
 * features contributed to the model output.
 */
data class XGBoostScoreResponse(
    val rawScore: Double,
    val shapValues: Map<String, Double>,
) {
    init {
        require(rawScore.isFinite()) { "xgboost raw score must be finite" }
        require(shapValues.keys.none { it.isBlank() }) { "shap feature names must not be blank" }
        require(shapValues.values.all { it.isFinite() }) { "shap values must be finite" }
    }
}

/**
 * Deterministic local stand-in for an XGBoost model client.
 *
 * This keeps the scoring contract testable before the real model sidecar exists. It should not be
 * treated as a production model implementation.
 */
class StubXGBoostScoreClient(
    private val rawScore: Double = 0.0,
    private val shapValues: Map<String, Double> = emptyMap(),
) : XGBoostScoreClient {
    override suspend fun score(
        context: ScoringContext,
        modelId: String,
    ): XGBoostScoreResponse = XGBoostScoreResponse(rawScore = rawScore, shapValues = shapValues)
}

/**
 * Scores an event with an XGBoost-style model output.
 *
 * [XGBoostScorer] calls an [XGBoostScoreClient], calibrates the raw model score with
 * [PlattCalibrator], and returns the calibrated probability as a [ScoreResult]. The model id is used
 * as the scorer version so decisions can be traced back to the model artifact that produced them.
 *
 * The real sidecar integration belongs behind [XGBoostScoreClient]. This class owns the scoring
 * contract: calibration, model-version attribution, latency capture, and factor mapping.
 */
class XGBoostScorer(
    override val name: String,
    private val modelId: String,
    private val client: XGBoostScoreClient = StubXGBoostScoreClient(),
    private val calibrator: PlattCalibrator = PlattCalibrator(),
) : Scorer {
    override val version: String = modelId

    init {
        require(name.isNotBlank()) { "scorer name must not be blank" }
        require(modelId.isNotBlank()) { "model id must not be blank" }
    }

    override suspend fun score(context: ScoringContext): ScoreResult {
        val startNanos = System.nanoTime()
        val response = client.score(context = context, modelId = modelId)

        return ScoreResult(
            score = calibrator.calibrate(response.rawScore),
            rawScore = response.rawScore,
            contributingFactors = response.shapValues.map { (name, contribution) ->
                factor(name = name, contribution = contribution)
            },
            modelVersion = version,
            latencyMs = elapsedMs(startNanos),
        )
    }
}
