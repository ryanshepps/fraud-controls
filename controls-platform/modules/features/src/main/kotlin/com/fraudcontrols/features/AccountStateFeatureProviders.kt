package com.fraudcontrols.features

import com.fraudcontrols.core.CustomerId
import com.fraudcontrols.core.FeatureValue
import com.fraudcontrols.core.ScoringContext

data class AccountState(
    val accountAgeDays: Double,
) {
    init {
        require(accountAgeDays.isFinite() && accountAgeDays >= 0.0) {
            "account age must be a finite non-negative number"
        }
    }
}

interface AccountStateStore {
    suspend fun accountState(customerId: CustomerId): AccountState?
    suspend fun isNewCounterparty(senderId: CustomerId, recipientId: CustomerId): Boolean?
}

class AccountStateFeatureProvider(
    override val featureName: String,
    private val store: AccountStateStore,
) : FeatureProvider {
    override suspend fun compute(context: ScoringContext): FeatureValue =
        when (featureName) {
            FraudFeatureNames.SENDER_ACCOUNT_AGE_DAYS -> accountAge(context.event.senderId)
            FraudFeatureNames.RECIPIENT_ACCOUNT_AGE_DAYS -> accountAge(context.event.recipientId)
            FraudFeatureNames.IS_NEW_COUNTERPARTY -> {
                val isNew = store.isNewCounterparty(context.event.senderId, context.event.recipientId)
                    ?: return FeatureValue.Unavailable("counterparty state unavailable")
                FeatureValue.BooleanValue(isNew)
            }
            else -> FeatureValue.Unavailable("account-state feature is not supported: $featureName")
        }

    private suspend fun accountAge(customerId: CustomerId): FeatureValue =
        store.accountState(customerId)
            ?.let { FeatureValue.NumberValue(it.accountAgeDays) }
            ?: FeatureValue.Unavailable("account state unavailable for ${customerId.value}")
}
