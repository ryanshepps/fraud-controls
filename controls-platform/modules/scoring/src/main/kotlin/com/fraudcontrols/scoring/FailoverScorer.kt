package com.fraudcontrols.scoring

import com.fraudcontrols.core.ScoreResult
import com.fraudcontrols.core.ScoringContext
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Orchestrates degraded-mode scoring for the scoring module.
 *
 * The scoring module treats each [Scorer] as a pluggable scoring contract. A scorer can be a
 * model-backed implementation, a [ShadowScorer] that records candidate model output without using
 * it for the live decision, or a [RuleBasedScorer] loaded from configuration.
 *
 * [FailoverScorer] is the boundary between normal model scoring and degraded scoring. It calls the
 * [primary] scorer first. If that scorer times out or fails with a runtime error, it calls the
 * [fallback] scorer and marks the returned [ScoreResult] as degraded. This allows downstream
 * decisioning to handle fallback output differently from normal model output.
 *
 * The fallback exists for model-service outages, timeouts, or bad responses. It is not a replacement
 * for the primary model path. Cancellation is rethrown so request shutdown and coroutine cancellation
 * are not converted into fallback decisions.
 */
class FailoverScorer(
    override val name: String,
    override val version: String,
    private val primary: Scorer,
    private val fallback: Scorer,
    private val timeout: Duration,
) : Scorer {
    init {
        require(name.isNotBlank()) { "scorer name must not be blank" }
        require(version.isNotBlank()) { "scorer version must not be blank" }
        require(!timeout.isNegative && !timeout.isZero) { "scorer timeout must be positive" }
    }

    override suspend fun score(context: ScoringContext): ScoreResult =
        try {
            withTimeout(timeout.toMillis()) {
                primary.score(context)
            }
        } catch (_: TimeoutCancellationException) {
            fallback.score(context).markDegraded()
        } catch (error: CancellationException) {
            throw error
        } catch (_: RuntimeException) {
            fallback.score(context).markDegraded()
        }

    private fun ScoreResult.markDegraded(): ScoreResult =
        copy(degraded = true)
}
