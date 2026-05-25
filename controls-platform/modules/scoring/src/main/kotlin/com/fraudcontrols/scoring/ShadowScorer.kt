package com.fraudcontrols.scoring

import com.fraudcontrols.core.EventId
import com.fraudcontrols.core.ScoreResult
import com.fraudcontrols.core.ScoringContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

interface ShadowEvaluationSink {
    suspend fun record(evaluations: List<ShadowEvaluation>)
}

data object NoopShadowEvaluationSink : ShadowEvaluationSink {
    override suspend fun record(evaluations: List<ShadowEvaluation>) = Unit
}

data class ShadowEvaluation(
    val eventId: EventId,
    val scorerName: String,
    val scorerVersion: String,
    val role: ShadowScorerRole,
    val result: ScoreResult?,
    val error: String?,
) {
    init {
        require((result == null) xor (error == null)) { "shadow evaluation must include exactly one result or error" }
        require(error == null || error.isNotBlank()) { "shadow evaluation error must not be blank" }
    }
}

enum class ShadowScorerRole {
    PRIMARY,
    SHADOW,
}

class ShadowScorer(
    override val name: String,
    private val primary: Scorer,
    private val shadows: List<Scorer>,
    private val sink: ShadowEvaluationSink = NoopShadowEvaluationSink,
) : Scorer {
    override val version: String = primary.version

    init {
        require(name.isNotBlank()) { "scorer name must not be blank" }
        require(shadows.isNotEmpty()) { "shadow scorer must include at least one shadow" }
    }

    override suspend fun score(context: ScoringContext): ScoreResult =
        coroutineScope {
            val primaryResult = async { primary.score(context) }
            val shadowResults = shadows.map { scorer ->
                async { scorer to scorer.captureScore(context) }
            }

            val result = primaryResult.await()
            val evaluations = buildList {
                add(
                    ShadowEvaluation(
                        eventId = context.eventId,
                        scorerName = primary.name,
                        scorerVersion = primary.version,
                        role = ShadowScorerRole.PRIMARY,
                        result = result,
                        error = null,
                    ),
                )
                for (shadowResult in shadowResults) {
                    val (scorer, scoreResult) = shadowResult.await()
                    add(scoreResult.toEvaluation(context.eventId, scorer))
                }
            }

            try {
                sink.record(evaluations)
            } catch (error: CancellationException) {
                throw error
            } catch (_: RuntimeException) {
                // Shadow reporting must not change the live decision path.
            }

            result
        }

    private fun Result<ScoreResult>.toEvaluation(
        eventId: EventId,
        scorer: Scorer,
    ): ShadowEvaluation =
        fold(
            onSuccess = {
                ShadowEvaluation(
                    eventId = eventId,
                    scorerName = scorer.name,
                    scorerVersion = scorer.version,
                    role = ShadowScorerRole.SHADOW,
                    result = it,
                    error = null,
                )
            },
            onFailure = {
                ShadowEvaluation(
                    eventId = eventId,
                    scorerName = scorer.name,
                    scorerVersion = scorer.version,
                    role = ShadowScorerRole.SHADOW,
                    result = null,
                    error = it.message ?: it::class.simpleName ?: "unknown error",
                )
            },
        )

    private suspend fun Scorer.captureScore(context: ScoringContext): Result<ScoreResult> =
        try {
            Result.success(score(context))
        } catch (error: CancellationException) {
            throw error
        } catch (error: RuntimeException) {
            Result.failure(error)
        }
}
