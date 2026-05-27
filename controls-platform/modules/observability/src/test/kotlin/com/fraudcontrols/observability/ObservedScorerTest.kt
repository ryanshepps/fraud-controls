package com.fraudcontrols.observability

import com.fraudcontrols.core.Factor
import com.fraudcontrols.core.CustomerId
import com.fraudcontrols.core.DeviceFingerprint
import com.fraudcontrols.core.EventId
import com.fraudcontrols.core.GeoPoint
import com.fraudcontrols.core.Money
import com.fraudcontrols.core.ScoreResult
import com.fraudcontrols.core.ScoringContext
import com.fraudcontrols.core.TransactionEvent
import com.fraudcontrols.core.TransactionType
import com.fraudcontrols.decisioning.DecisioningTracer
import com.fraudcontrols.scoring.Scorer
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ObservedScorerTest {
    @Test
    fun `records scoring latency inside a scorer trace span`() = runTest {
        val tracer = RecordingDecisioningTracer()
        val metrics = MicrometerControlsMetrics()
        val scorer = ObservedScorer(
            delegate = FixedScorer(),
            metrics = metrics,
            tracer = tracer,
        )

        scorer.score(ScoringContext(sampleEvent()))

        assertEquals(listOf("decision.scorer.score"), tracer.spans.map { it.name })
        assertEquals("fixed", tracer.spans.single().attributes["scorer.name"])
        assertEquals("fixed-v1", tracer.spans.single().attributes["scorer.version"])
        assertEquals("evt-1", tracer.spans.single().attributes["event.id"])
        assert(metrics.scrape().contains("controls_scoring_latency_seconds_bucket"))
    }
}

private class FixedScorer : Scorer {
    override val name: String = "fixed"
    override val version: String = "fixed-v1"

    override suspend fun score(context: ScoringContext): ScoreResult =
        ScoreResult(
            score = 0.5,
            rawScore = null,
            contributingFactors = listOf(Factor(name = "test_score", contribution = 0.1)),
            modelVersion = version,
            latencyMs = 1.0,
        )
}

private fun sampleEvent(): TransactionEvent =
    TransactionEvent(
        eventId = EventId("evt-1"),
        timestamp = Instant.parse("2026-01-01T00:00:00Z"),
        senderId = CustomerId("sender-1"),
        recipientId = CustomerId("recipient-1"),
        amount = Money.usd("25.00"),
        transactionType = TransactionType.P2P_SEND,
        senderDeviceFingerprint = DeviceFingerprint("device-1"),
        senderGeo = GeoPoint(latitude = 43.6532, longitude = -79.3832),
        senderBalanceBefore = BigDecimal("2000.00"),
        senderBalanceAfter = BigDecimal("1975.00"),
        recipientBalanceBefore = BigDecimal("50.00"),
        recipientBalanceAfter = BigDecimal("75.00"),
        senderAccountAgeDays = 30.0,
        recipientAccountAgeDays = 120.0,
        isNewCounterparty = true,
    )

private class RecordingDecisioningTracer : DecisioningTracer {
    val spans = mutableListOf<RecordedSpan>()

    override suspend fun <T> span(
        name: String,
        attributes: Map<String, String>,
        block: suspend () -> T,
    ): T {
        spans += RecordedSpan(name = name, attributes = attributes)
        return block()
    }
}

private data class RecordedSpan(
    val name: String,
    val attributes: Map<String, String>,
)
