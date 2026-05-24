package com.fraudcontrols.streaming

import com.fraudcontrols.core.CustomerId
import com.fraudcontrols.core.EventId
import com.fraudcontrols.core.GeoPoint
import com.fraudcontrols.core.Money
import com.fraudcontrols.core.TransactionType
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FraudgenEventParserTest {
    private val parser = FraudgenEventParser()

    @Test
    fun `parses fraudgen json into transaction event`() {
        val event = parser.parse(validPayload())

        assertEquals(EventId("evt-1"), event.eventId)
        assertEquals(Instant.parse("2026-01-01T12:00:00Z"), event.timestamp)
        assertEquals(CustomerId("sender-1"), event.senderId)
        assertEquals(CustomerId("recipient-1"), event.recipientId)
        assertEquals(Money.usd("25.50"), event.amount)
        assertEquals(TransactionType.P2P_SEND, event.transactionType)
        assertEquals(GeoPoint(latitude = 43.6532, longitude = -79.3832), event.senderGeo)
        assertEquals(BigDecimal("100.00"), event.senderBalanceBefore)
        assertEquals(BigDecimal("74.50"), event.senderBalanceAfter)
        assertEquals(0.125, event.senderAccountAgeDays)
        assertEquals(120.5, event.recipientAccountAgeDays)
        assertEquals(true, event.isNewCounterparty)
    }

    @Test
    fun `accepts csv-shaped money strings`() {
        val event = parser.parse(
            validPayload(
                """"amount": "25.50"""",
                """"sender_balance_before": "100.00"""",
                """"sender_balance_after": "74.50"""",
            ),
        )

        assertEquals(Money.usd("25.50"), event.amount)
        assertEquals(BigDecimal("100.00"), event.senderBalanceBefore)
        assertEquals(BigDecimal("74.50"), event.senderBalanceAfter)
    }

    @Test
    fun `rejects invalid payloads with contract context`() {
        val cases = listOf(
            "{}" to "missing required field: event_id",
            validPayload(""""type": "wire"""") to "unsupported transaction type: wire",
            validPayload(""""sender_geo": {"lat": 91.0, "lng": -79.3832}""") to "latitude must be between -90 and 90",
            validPayload(""""amount": 25.505""") to "field amount must have cents precision",
        )

        for ((payload, expectedMessage) in cases) {
            val error = assertFailsWith<FraudgenEventParseException>(expectedMessage) {
                parser.parse(payload)
            }
            assertContains(error.message.orEmpty(), expectedMessage)
        }
    }

    private fun validPayload(vararg replacements: String): String {
        val fields = linkedMapOf(
            "event_id" to """"event_id": "evt-1"""",
            "timestamp" to """"timestamp": "2026-01-01T12:00:00+00:00"""",
            "sender_id" to """"sender_id": "sender-1"""",
            "recipient_id" to """"recipient_id": "recipient-1"""",
            "amount" to """"amount": 25.50""",
            "currency" to """"currency": "USD"""",
            "type" to """"type": "p2p_send"""",
            "sender_device_fingerprint" to """"sender_device_fingerprint": "device-1"""",
            "sender_geo" to """"sender_geo": {"lat": 43.6532, "lng": -79.3832}""",
            "sender_balance_before" to """"sender_balance_before": 100.00""",
            "sender_balance_after" to """"sender_balance_after": 74.50""",
            "recipient_balance_before" to """"recipient_balance_before": 50.00""",
            "recipient_balance_after" to """"recipient_balance_after": 75.50""",
            "sender_account_age_days" to """"sender_account_age_days": 0.125""",
            "recipient_account_age_days" to """"recipient_account_age_days": 120.5""",
            "is_new_counterparty" to """"is_new_counterparty": true""",
        )

        for (replacement in replacements) {
            val fieldName = replacement.substringAfter("\"").substringBefore("\"")
            fields[fieldName] = replacement
        }

        return fields.values.joinToString(prefix = "{", separator = ",", postfix = "}")
    }
}
