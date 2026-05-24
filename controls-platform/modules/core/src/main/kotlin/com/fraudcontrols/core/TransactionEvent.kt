package com.fraudcontrols.core

import java.math.BigDecimal
import java.time.Instant

data class TransactionEvent(
    val eventId: EventId,
    val timestamp: Instant,
    val senderId: CustomerId,
    val recipientId: CustomerId,
    val amount: Money,
    val transactionType: TransactionType,
    val senderDeviceFingerprint: DeviceFingerprint,
    val senderGeo: GeoPoint,
    val senderBalanceBefore: BigDecimal,
    val senderBalanceAfter: BigDecimal,
    val recipientBalanceBefore: BigDecimal,
    val recipientBalanceAfter: BigDecimal,
    val senderAccountAgeDays: Double,
    val recipientAccountAgeDays: Double,
    val isNewCounterparty: Boolean,
) {
    init {
        require(amount.isPositive()) { "transaction amount must be positive" }
        require(senderId != recipientId) { "sender and recipient must differ" }
        require(senderAccountAgeDays.isFinite() && senderAccountAgeDays >= 0) {
            "sender account age must be a finite non-negative number"
        }
        require(recipientAccountAgeDays.isFinite() && recipientAccountAgeDays >= 0) {
            "recipient account age must be a finite non-negative number"
        }
    }
}

enum class TransactionType {
    P2P_SEND,
    P2P_PAYMENT,
    CASH_OUT,
    CARD_PAYMENT,
    BANK_TRANSFER,
}

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(latitude in -90.0..90.0) { "latitude must be between -90 and 90" }
        require(longitude in -180.0..180.0) { "longitude must be between -180 and 180" }
    }
}
