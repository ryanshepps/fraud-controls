package com.fraudcontrols.features

import com.fraudcontrols.core.FeatureValue
import com.fraudcontrols.core.ScoringContext
import java.math.BigDecimal
import java.math.RoundingMode

class EventFeatureProvider(
    override val featureName: String,
) : FeatureProvider {
    override suspend fun compute(context: ScoringContext): FeatureValue =
        when (featureName) {
            FraudFeatureNames.AMOUNT -> number(context.event.amount.amount)
            FraudFeatureNames.CURRENCY -> FeatureValue.TextValue(context.event.amount.currency.currencyCode)
            FraudFeatureNames.TRANSACTION_TYPE -> FeatureValue.TextValue(context.event.transactionType.name)
            FraudFeatureNames.SENDER_BALANCE_BEFORE -> number(context.event.senderBalanceBefore)
            FraudFeatureNames.SENDER_BALANCE_AFTER -> number(context.event.senderBalanceAfter)
            FraudFeatureNames.RECIPIENT_BALANCE_BEFORE -> number(context.event.recipientBalanceBefore)
            FraudFeatureNames.RECIPIENT_BALANCE_AFTER -> number(context.event.recipientBalanceAfter)
            FraudFeatureNames.SENDER_ACCOUNT_AGE_DAYS -> FeatureValue.NumberValue(context.event.senderAccountAgeDays)
            FraudFeatureNames.RECIPIENT_ACCOUNT_AGE_DAYS -> FeatureValue.NumberValue(context.event.recipientAccountAgeDays)
            FraudFeatureNames.IS_NEW_COUNTERPARTY -> FeatureValue.BooleanValue(context.event.isNewCounterparty)
            FraudFeatureNames.SENDER_GEO_LATITUDE -> FeatureValue.NumberValue(context.event.senderGeo.latitude)
            FraudFeatureNames.SENDER_GEO_LONGITUDE -> FeatureValue.NumberValue(context.event.senderGeo.longitude)
            FraudFeatureNames.SENDER_BALANCE_DELTA -> number(context.event.senderBalanceAfter - context.event.senderBalanceBefore)
            FraudFeatureNames.RECIPIENT_BALANCE_DELTA -> {
                number(context.event.recipientBalanceAfter - context.event.recipientBalanceBefore)
            }
            FraudFeatureNames.AMOUNT_TO_SENDER_BALANCE_RATIO -> amountToSenderBalanceRatio(context)
            else -> FeatureValue.Unavailable("event feature is not supported: $featureName")
        }

    private fun amountToSenderBalanceRatio(context: ScoringContext): FeatureValue =
        if (context.event.senderBalanceBefore > BigDecimal.ZERO) {
            FeatureValue.NumberValue(
                context.event.amount.amount
                    .divide(context.event.senderBalanceBefore, RATIO_SCALE, RoundingMode.HALF_UP)
                    .toDouble(),
            )
        } else {
            FeatureValue.Unavailable("sender balance before is not positive")
        }

    private fun number(value: BigDecimal): FeatureValue =
        FeatureValue.NumberValue(value.toDouble())

    private companion object {
        const val RATIO_SCALE = 6
    }
}

fun defaultEventFeatureProviders(): List<FeatureProvider> =
    listOf(
        FraudFeatureNames.AMOUNT,
        FraudFeatureNames.CURRENCY,
        FraudFeatureNames.TRANSACTION_TYPE,
        FraudFeatureNames.SENDER_BALANCE_BEFORE,
        FraudFeatureNames.SENDER_BALANCE_AFTER,
        FraudFeatureNames.RECIPIENT_BALANCE_BEFORE,
        FraudFeatureNames.RECIPIENT_BALANCE_AFTER,
        FraudFeatureNames.SENDER_ACCOUNT_AGE_DAYS,
        FraudFeatureNames.RECIPIENT_ACCOUNT_AGE_DAYS,
        FraudFeatureNames.IS_NEW_COUNTERPARTY,
        FraudFeatureNames.SENDER_GEO_LATITUDE,
        FraudFeatureNames.SENDER_GEO_LONGITUDE,
        FraudFeatureNames.SENDER_BALANCE_DELTA,
        FraudFeatureNames.RECIPIENT_BALANCE_DELTA,
        FraudFeatureNames.AMOUNT_TO_SENDER_BALANCE_RATIO,
    ).map(::EventFeatureProvider)
