package com.fraudcontrols.scoring

import com.fraudcontrols.core.ScoreResult
import com.fraudcontrols.core.ScoringContext

interface XGBoostScoreClient {
    suspend fun score(
        context: ScoringContext,
        modelId: String,
    ): XGBoostScoreResponse
}

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

class StubXGBoostScoreClient(
    private val rawScore: Double = 0.0,
    private val shapValues: Map<String, Double> = emptyMap(),
) : XGBoostScoreClient {
    override suspend fun score(
        context: ScoringContext,
        modelId: String,
    ): XGBoostScoreResponse =
        XGBoostScoreResponse(rawScore = rawScore, shapValues = shapValues)
}

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
