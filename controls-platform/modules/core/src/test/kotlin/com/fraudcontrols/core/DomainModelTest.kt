package com.fraudcontrols.core

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DomainModelTest {
    @Test
    fun `transaction event rejects non-positive amounts`() {
        assertFailsWith<IllegalArgumentException> {
            sampleEvent(amount = Money.usd("0.00"))
        }
    }

    @Test
    fun `transaction event accepts a valid fraudgen shaped event`() {
        val event = sampleEvent()

        assertEquals(EventId("evt-1"), event.eventId)
        assertEquals(TransactionType.P2P_SEND, event.transactionType)
        assertEquals(true, event.isNewCounterparty)
    }

    @Test
    fun `transaction event rejects invalid fractional account ages`() {
        val invalidAges = listOf(-0.001, Double.NaN, Double.POSITIVE_INFINITY)

        for (age in invalidAges) {
            assertFailsWith<IllegalArgumentException>("sender age $age should be invalid") {
                sampleEvent(senderAccountAgeDays = age)
            }
            assertFailsWith<IllegalArgumentException>("recipient age $age should be invalid") {
                sampleEvent(recipientAccountAgeDays = age)
            }
        }
    }

    @Test
    fun `score result requires probability score`() {
        assertFailsWith<IllegalArgumentException> {
            ScoreResult(
                score = 1.2,
                rawScore = 4.0,
                contributingFactors = emptyList(),
                modelVersion = "heuristic_v1",
                latencyMs = 5.0,
            )
        }
    }

    @Test
    fun `score result requires audit metadata`() {
        assertFailsWith<IllegalArgumentException> {
            ScoreResult(
                score = 0.5,
                rawScore = Double.NaN,
                contributingFactors = emptyList(),
                modelVersion = "heuristic_v1",
                latencyMs = 5.0,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            ScoreResult(
                score = 0.5,
                rawScore = null,
                contributingFactors = emptyList(),
                modelVersion = "",
                latencyMs = 5.0,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            ScoreResult(
                score = 0.5,
                rawScore = null,
                contributingFactors = emptyList(),
                modelVersion = "heuristic_v1",
                latencyMs = Double.POSITIVE_INFINITY,
            )
        }
    }

    @Test
    fun `unavailable feature values require an explicit reason`() {
        assertFailsWith<IllegalArgumentException> {
            FeatureValue.Unavailable("")
        }
    }

    @Test
    fun `missing feature values require an explicit reason`() {
        assertFailsWith<IllegalArgumentException> {
            FeatureValue.Missing("")
        }
    }

    @Test
    fun `numeric feature values must be finite`() {
        assertFailsWith<IllegalArgumentException> {
            FeatureValue.NumberValue(Double.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            FeatureValue.NumberValue(Double.NEGATIVE_INFINITY)
        }
    }

    private fun sampleEvent(
        amount: Money = Money.usd("25.00"),
        senderAccountAgeDays: Double = 15.125,
        recipientAccountAgeDays: Double = 120.5,
    ): TransactionEvent = TransactionEvent(
        eventId = EventId("evt-1"),
        timestamp = Instant.parse("2026-01-01T00:00:00Z"),
        senderId = CustomerId("sender-1"),
        recipientId = CustomerId("recipient-1"),
        amount = amount,
        transactionType = TransactionType.P2P_SEND,
        senderDeviceFingerprint = DeviceFingerprint("device-1"),
        senderGeo = GeoPoint(latitude = 43.6532, longitude = -79.3832),
        senderBalanceBefore = BigDecimal("100.00"),
        senderBalanceAfter = BigDecimal("75.00"),
        recipientBalanceBefore = BigDecimal("50.00"),
        recipientBalanceAfter = BigDecimal("75.00"),
        senderAccountAgeDays = senderAccountAgeDays,
        recipientAccountAgeDays = recipientAccountAgeDays,
        isNewCounterparty = true,
    )
}
