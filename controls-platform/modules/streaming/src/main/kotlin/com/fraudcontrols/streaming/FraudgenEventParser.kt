package com.fraudcontrols.streaming

import com.fraudcontrols.core.CustomerId
import com.fraudcontrols.core.DeviceFingerprint
import com.fraudcontrols.core.EventId
import com.fraudcontrols.core.GeoPoint
import com.fraudcontrols.core.Money
import com.fraudcontrols.core.TransactionEvent
import com.fraudcontrols.core.TransactionType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime
import java.util.Currency

class FraudgenEventParser(
    private val json: Json = Json,
) {
    fun parse(payload: String): TransactionEvent {
        val root =
            try {
                json.parseToJsonElement(payload).jsonObject
            } catch (error: IllegalArgumentException) {
                throw FraudgenEventParseException("payload must be a JSON object", error)
            }

        return try {
            TransactionEvent(
                eventId = EventId(root.requiredString("event_id")),
                timestamp = OffsetDateTime.parse(root.requiredString("timestamp")).toInstant(),
                senderId = CustomerId(root.requiredString("sender_id")),
                recipientId = CustomerId(root.requiredString("recipient_id")),
                amount = Money(
                    amount = root.requiredCents("amount"),
                    currency = Currency.getInstance(root.requiredString("currency")),
                ),
                transactionType = parseTransactionType(root.requiredString("type")),
                senderDeviceFingerprint = DeviceFingerprint(root.requiredString("sender_device_fingerprint")),
                senderGeo = root.requiredGeoPoint("sender_geo"),
                senderBalanceBefore = root.requiredCents("sender_balance_before"),
                senderBalanceAfter = root.requiredCents("sender_balance_after"),
                recipientBalanceBefore = root.requiredCents("recipient_balance_before"),
                recipientBalanceAfter = root.requiredCents("recipient_balance_after"),
                senderAccountAgeDays = root.requiredDouble("sender_account_age_days"),
                recipientAccountAgeDays = root.requiredDouble("recipient_account_age_days"),
                isNewCounterparty = root.requiredBoolean("is_new_counterparty"),
            )
        } catch (error: FraudgenEventParseException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw FraudgenEventParseException("invalid fraudgen event: ${error.message}", error)
        }
    }

    private fun parseTransactionType(rawType: String): TransactionType = when (rawType) {
        "p2p_send" -> TransactionType.P2P_SEND
        "p2p_payment" -> TransactionType.P2P_PAYMENT
        "cash_out" -> TransactionType.CASH_OUT
        "card_payment" -> TransactionType.CARD_PAYMENT
        "bank_transfer" -> TransactionType.BANK_TRANSFER
        else -> throw FraudgenEventParseException("unsupported transaction type: $rawType")
    }
}

class FraudgenEventParseException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

private fun JsonObject.required(name: String): JsonElement = this[name] ?: throw FraudgenEventParseException("missing required field: $name")

private fun JsonObject.requiredObject(name: String): JsonObject = required(name) as? JsonObject
    ?: throw FraudgenEventParseException("field $name must be an object")

private fun JsonObject.requiredString(name: String): String {
    val primitive = requiredPrimitive(name)
    val value = primitive.content.trim()
    if (value.isEmpty()) {
        throw FraudgenEventParseException("field $name must not be blank")
    }
    return value
}

private fun JsonObject.requiredDouble(name: String): Double {
    val value = requiredPrimitive(name).doubleOrNull
        ?: throw FraudgenEventParseException("field $name must be a number")
    if (!value.isFinite()) {
        throw FraudgenEventParseException("field $name must be finite")
    }
    return value
}

private fun JsonObject.requiredBoolean(name: String): Boolean = requiredPrimitive(name).booleanOrNull
    ?: throw FraudgenEventParseException("field $name must be a boolean")

private fun JsonObject.requiredGeoPoint(name: String): GeoPoint {
    val geo = requiredObject(name)
    return GeoPoint(
        latitude = geo.requiredDouble("lat"),
        longitude = geo.requiredDouble("lng"),
    )
}

private fun JsonObject.requiredCents(name: String): BigDecimal {
    val rawValue = requiredPrimitive(name).content
    return try {
        BigDecimal(rawValue).setScale(2, RoundingMode.UNNECESSARY)
    } catch (error: NumberFormatException) {
        throw FraudgenEventParseException("field $name must be decimal money", error)
    } catch (error: ArithmeticException) {
        throw FraudgenEventParseException("field $name must have cents precision", error)
    }
}

private fun JsonObject.requiredPrimitive(name: String): JsonPrimitive = required(name).jsonPrimitive
