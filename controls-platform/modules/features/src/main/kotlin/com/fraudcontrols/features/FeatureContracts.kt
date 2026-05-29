package com.fraudcontrols.features

import com.fraudcontrols.core.EventId
import com.fraudcontrols.core.FeatureSnapshot
import com.fraudcontrols.core.FeatureValue
import com.fraudcontrols.core.ScoringContext

interface FeatureProvider {
    val featureName: String
    suspend fun compute(context: ScoringContext): FeatureValue
}

class FeatureResolver(
    providers: Iterable<FeatureProvider>,
) {
    private val providersByName = providers.associateBy { it.featureName }

    init {
        require(providersByName.size == providers.count()) { "feature providers must have unique names" }
    }

    fun request(context: ScoringContext): FeatureResolutionRequest = FeatureResolutionRequest(
        eventId = context.eventId,
        context = context,
        providersByName = providersByName,
    )
}

class FeatureResolutionRequest internal constructor(
    private val eventId: EventId,
    private val context: ScoringContext,
    private val providersByName: Map<String, FeatureProvider>,
) {
    private val cache = mutableMapOf<String, FeatureValue>()

    suspend fun resolve(featureName: String): FeatureValue {
        require(featureName.isNotBlank()) { "feature name must not be blank" }
        return cache.getOrPut(featureName) {
            val provider = providersByName[featureName]
                ?: return@getOrPut FeatureValue.Unavailable("no provider registered for feature: $featureName")
            try {
                provider.compute(context)
            } catch (error: RuntimeException) {
                FeatureValue.Unavailable(
                    "feature $featureName failed: ${error.message ?: error::class.simpleName.orEmpty()}",
                )
            }
        }
    }

    suspend fun resolveAll(featureNames: Iterable<String>): FeatureSnapshot {
        val values = linkedMapOf<String, FeatureValue>()
        for (featureName in featureNames) {
            values[featureName] = resolve(featureName)
        }
        return FeatureSnapshot(eventId = eventId, values = values)
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
    const val SENDER_SEND_COUNT_5M = "sender_send_count_5m"
    const val SENDER_SEND_COUNT_1H = "sender_send_count_1h"
    const val SENDER_SEND_COUNT_24H = "sender_send_count_24h"
    const val SENDER_SEND_AMOUNT_SUM_5M = "sender_send_amount_sum_5m"
    const val SENDER_SEND_AMOUNT_SUM_1H = "sender_send_amount_sum_1h"
    const val SENDER_SEND_AMOUNT_SUM_24H = "sender_send_amount_sum_24h"
    const val RECIPIENT_IN_DEGREE_7D = "recipient_in_degree_7d"
    const val SENDER_RECIPIENT_PRIOR_SEND_COUNT = "sender_recipient_prior_send_count"
    const val DEVICE_DISTINCT_ACCOUNTS_30D = "device_distinct_accounts_30d"
    const val IMPOSSIBLE_TRAVEL_FROM_LAST_LOGIN = "impossible_travel_from_last_login"
    const val FRAUD_MODEL_SCORE = "fraud_model_score"
}
