package com.fraudcontrols.features

import com.fraudcontrols.core.FeatureSnapshot
import com.fraudcontrols.core.FeatureValue
import com.fraudcontrols.core.TransactionEvent
import java.math.BigDecimal
import java.math.RoundingMode

class FraudFeatureExtractor {
    fun extract(event: TransactionEvent): FeatureSnapshot =
        FeatureSnapshot(
            eventId = event.eventId,
            values = buildMap {
                putNumber(FraudFeatureNames.AMOUNT, event.amount.amount)
                putText(FraudFeatureNames.CURRENCY, event.amount.currency.currencyCode)
                putText(FraudFeatureNames.TRANSACTION_TYPE, event.transactionType.name)
                putNumber(FraudFeatureNames.SENDER_BALANCE_BEFORE, event.senderBalanceBefore)
                putNumber(FraudFeatureNames.SENDER_BALANCE_AFTER, event.senderBalanceAfter)
                putNumber(FraudFeatureNames.RECIPIENT_BALANCE_BEFORE, event.recipientBalanceBefore)
                putNumber(FraudFeatureNames.RECIPIENT_BALANCE_AFTER, event.recipientBalanceAfter)
                putNumber(FraudFeatureNames.SENDER_ACCOUNT_AGE_DAYS, event.senderAccountAgeDays)
                putNumber(FraudFeatureNames.RECIPIENT_ACCOUNT_AGE_DAYS, event.recipientAccountAgeDays)
                putBoolean(FraudFeatureNames.IS_NEW_COUNTERPARTY, event.isNewCounterparty)
                putNumber(FraudFeatureNames.SENDER_GEO_LATITUDE, event.senderGeo.latitude)
                putNumber(FraudFeatureNames.SENDER_GEO_LONGITUDE, event.senderGeo.longitude)
                putNumber(FraudFeatureNames.SENDER_BALANCE_DELTA, event.senderBalanceAfter - event.senderBalanceBefore)
                putNumber(
                    FraudFeatureNames.RECIPIENT_BALANCE_DELTA,
                    event.recipientBalanceAfter - event.recipientBalanceBefore,
                )
                putRatio(
                    name = FraudFeatureNames.AMOUNT_TO_SENDER_BALANCE_RATIO,
                    numerator = event.amount.amount,
                    denominator = event.senderBalanceBefore,
                    missingReason = "sender balance before is not positive",
                )
            },
        )

    private fun MutableMap<String, FeatureValue>.putNumber(
        name: String,
        value: BigDecimal,
    ) {
        putNumber(name, value.toDouble())
    }

    private fun MutableMap<String, FeatureValue>.putNumber(
        name: String,
        value: Double,
    ) {
        this[name] = FeatureValue.NumberValue(value)
    }

    private fun MutableMap<String, FeatureValue>.putBoolean(
        name: String,
        value: Boolean,
    ) {
        this[name] = FeatureValue.BooleanValue(value)
    }

    private fun MutableMap<String, FeatureValue>.putText(
        name: String,
        value: String,
    ) {
        this[name] = FeatureValue.TextValue(value)
    }

    private fun MutableMap<String, FeatureValue>.putRatio(
        name: String,
        numerator: BigDecimal,
        denominator: BigDecimal,
        missingReason: String,
    ) {
        this[name] =
            if (denominator > BigDecimal.ZERO) {
                FeatureValue.NumberValue(
                    numerator.divide(denominator, RATIO_SCALE, RoundingMode.HALF_UP).toDouble(),
                )
            } else {
                FeatureValue.Missing(missingReason)
            }
    }

    private companion object {
        const val RATIO_SCALE = 6
    }
}

object FraudFeatureNames {
    const val AMOUNT = "amount"
    const val CURRENCY = "currency"
    const val TRANSACTION_TYPE = "transaction_type"
    const val SENDER_BALANCE_BEFORE = "sender_balance_before"
    const val SENDER_BALANCE_AFTER = "sender_balance_after"
    const val RECIPIENT_BALANCE_BEFORE = "recipient_balance_before"
    const val RECIPIENT_BALANCE_AFTER = "recipient_balance_after"
    const val SENDER_ACCOUNT_AGE_DAYS = "sender_account_age_days"
    const val RECIPIENT_ACCOUNT_AGE_DAYS = "recipient_account_age_days"
    const val IS_NEW_COUNTERPARTY = "is_new_counterparty"
    const val SENDER_GEO_LATITUDE = "sender_geo_latitude"
    const val SENDER_GEO_LONGITUDE = "sender_geo_longitude"
    const val SENDER_BALANCE_DELTA = "sender_balance_delta"
    const val RECIPIENT_BALANCE_DELTA = "recipient_balance_delta"
    const val AMOUNT_TO_SENDER_BALANCE_RATIO = "amount_to_sender_balance_ratio"
}
