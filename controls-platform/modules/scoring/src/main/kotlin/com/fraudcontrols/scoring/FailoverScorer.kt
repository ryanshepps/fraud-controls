package com.fraudcontrols.scoring

import com.fraudcontrols.core.ScoreResult
import com.fraudcontrols.core.ScoringContext
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

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
