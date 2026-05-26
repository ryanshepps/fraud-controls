package com.fraudcontrols.observability

import com.fraudcontrols.core.ScoreResult
import com.fraudcontrols.core.ScoringContext
import com.fraudcontrols.scoring.Scorer
import kotlinx.coroutines.CancellationException

class ObservedScorer(
    private val delegate: Scorer,
    private val metrics: ControlsMetrics,
) : Scorer {
    override val name: String = delegate.name
    override val version: String = delegate.version

    override suspend fun score(context: ScoringContext): ScoreResult {
        val startedAt = System.nanoTime()
        try {
            val result = delegate.score(context)
            metrics.recordScoringLatency(
                scorerName = delegate.name,
                scorerVersion = delegate.version,
                degraded = result.degraded,
                latencyMs = elapsedMs(startedAt),
            )
            return result
        } catch (error: CancellationException) {
            throw error
        } catch (error: RuntimeException) {
            metrics.recordScoringLatency(
                scorerName = delegate.name,
                scorerVersion = delegate.version,
                degraded = true,
                latencyMs = elapsedMs(startedAt),
            )
            throw error
        }
    }

    private fun elapsedMs(startedAt: Long): Double =
        (System.nanoTime() - startedAt) / 1_000_000.0
}
