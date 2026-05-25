package com.fraudcontrols.features

import com.fraudcontrols.core.CustomerId
import com.fraudcontrols.core.DeviceFingerprint
import com.fraudcontrols.core.EventId
import com.fraudcontrols.core.FeatureValue
import com.fraudcontrols.core.GeoPoint
import com.fraudcontrols.core.Money
import com.fraudcontrols.core.TransactionEvent
import com.fraudcontrols.core.TransactionType
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class FraudFeatureExtractorTest {
    private val extractor = FraudFeatureExtractor()

    @Test
    fun `extracts stable transaction features`() {
        val snapshot = extractor.extract(sampleEvent())

        assertEquals(EventId("evt-1"), snapshot.eventId)
        assertEquals(FeatureValue.NumberValue(25.0), snapshot.values[FraudFeatureNames.AMOUNT])
        assertEquals(FeatureValue.TextValue("USD"), snapshot.values[FraudFeatureNames.CURRENCY])
        assertEquals(FeatureValue.TextValue("P2P_SEND"), snapshot.values[FraudFeatureNames.TRANSACTION_TYPE])
        assertEquals(FeatureValue.NumberValue(100.0), snapshot.values[FraudFeatureNames.SENDER_BALANCE_BEFORE])
        assertEquals(FeatureValue.NumberValue(75.0), snapshot.values[FraudFeatureNames.SENDER_BALANCE_AFTER])
        assertEquals(FeatureValue.NumberValue(50.0), snapshot.values[FraudFeatureNames.RECIPIENT_BALANCE_BEFORE])
        assertEquals(FeatureValue.NumberValue(75.0), snapshot.values[FraudFeatureNames.RECIPIENT_BALANCE_AFTER])
        assertEquals(FeatureValue.NumberValue(0.125), snapshot.values[FraudFeatureNames.SENDER_ACCOUNT_AGE_DAYS])
        assertEquals(FeatureValue.NumberValue(120.5), snapshot.values[FraudFeatureNames.RECIPIENT_ACCOUNT_AGE_DAYS])
        assertEquals(FeatureValue.BooleanValue(true), snapshot.values[FraudFeatureNames.IS_NEW_COUNTERPARTY])
        assertEquals(FeatureValue.NumberValue(43.6532), snapshot.values[FraudFeatureNames.SENDER_GEO_LATITUDE])
        assertEquals(FeatureValue.NumberValue(-79.3832), snapshot.values[FraudFeatureNames.SENDER_GEO_LONGITUDE])
        assertEquals(FeatureValue.NumberValue(-25.0), snapshot.values[FraudFeatureNames.SENDER_BALANCE_DELTA])
        assertEquals(FeatureValue.NumberValue(25.0), snapshot.values[FraudFeatureNames.RECIPIENT_BALANCE_DELTA])
        assertEquals(
            FeatureValue.NumberValue(0.25),
            snapshot.values[FraudFeatureNames.AMOUNT_TO_SENDER_BALANCE_RATIO],
        )
    }

    @Test
    fun `marks sender balance ratio missing when denominator is unavailable`() {
        val snapshot = extractor.extract(
            sampleEvent(
                senderBalanceBefore = BigDecimal("0.00"),
                senderBalanceAfter = BigDecimal("-25.00"),
            ),
        )

        assertEquals(
            FeatureValue.Missing("sender balance before is not positive"),
            snapshot.values[FraudFeatureNames.AMOUNT_TO_SENDER_BALANCE_RATIO],
        )
    }

    private fun sampleEvent(
        senderBalanceBefore: BigDecimal = BigDecimal("100.00"),
        senderBalanceAfter: BigDecimal = BigDecimal("75.00"),
    ): TransactionEvent =
        TransactionEvent(
            eventId = EventId("evt-1"),
            timestamp = Instant.parse("2026-01-01T00:00:00Z"),
            senderId = CustomerId("sender-1"),
            recipientId = CustomerId("recipient-1"),
            amount = Money.usd("25.00"),
            transactionType = TransactionType.P2P_SEND,
            senderDeviceFingerprint = DeviceFingerprint("device-1"),
            senderGeo = GeoPoint(latitude = 43.6532, longitude = -79.3832),
            senderBalanceBefore = senderBalanceBefore,
            senderBalanceAfter = senderBalanceAfter,
            recipientBalanceBefore = BigDecimal("50.00"),
            recipientBalanceAfter = BigDecimal("75.00"),
            senderAccountAgeDays = 0.125,
            recipientAccountAgeDays = 120.5,
            isNewCounterparty = true,
        )
}
