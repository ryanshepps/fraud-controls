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
        assertEquals(TransactionType.P2P_PAYMENT, event.transactionType)
        assertEquals(true, event.isNewCounterparty)
    }

    @Test
    fun `score result requires probability score`() {
        assertFailsWith<IllegalArgumentException> {
            ScoreResult(
                score = 1.2,
                band = RiskBand.HIGH,
                factors = emptyList(),
                latencyMs = 5,
            )
        }
    }

    @Test
    fun `missing feature values require an explicit reason`() {
        assertFailsWith<IllegalArgumentException> {
            FeatureValue.Missing("")
        }
    }

    private fun sampleEvent(amount: Money = Money.usd("25.00")): TransactionEvent =
        TransactionEvent(
            eventId = EventId("evt-1"),
            timestamp = Instant.parse("2026-01-01T00:00:00Z"),
            senderId = CustomerId("sender-1"),
            recipientId = CustomerId("recipient-1"),
            amount = amount,
            transactionType = TransactionType.P2P_PAYMENT,
            senderDeviceFingerprint = DeviceFingerprint("device-1"),
            senderGeo = GeoPoint(latitude = 43.6532, longitude = -79.3832),
            senderBalanceBefore = BigDecimal("100.00"),
            senderBalanceAfter = BigDecimal("75.00"),
            recipientBalanceBefore = BigDecimal("50.00"),
            recipientBalanceAfter = BigDecimal("75.00"),
            senderAccountAgeDays = 15,
            recipientAccountAgeDays = 120,
            isNewCounterparty = true,
        )
}
