package com.fraudcontrols.features

import com.fraudcontrols.core.CustomerId
import com.fraudcontrols.core.FeatureValue
import com.fraudcontrols.core.ScoringContext
import java.time.Instant

interface GraphFeatureStore {
    suspend fun recipientInDegree7d(recipientId: CustomerId, asOf: Instant): Double?
    suspend fun senderRecipientPriorSendCount(
        senderId: CustomerId,
        recipientId: CustomerId,
        asOf: Instant,
    ): Double?
}

class GraphFeatureProvider(
    override val featureName: String,
    private val store: GraphFeatureStore,
) : FeatureProvider {
    override suspend fun compute(context: ScoringContext): FeatureValue = when (featureName) {
        FraudFeatureNames.RECIPIENT_IN_DEGREE_7D -> {
            store.recipientInDegree7d(context.event.recipientId, context.event.timestamp)
                ?.let { FeatureValue.NumberValue(it) }
                ?: FeatureValue.Unavailable("recipient graph aggregate unavailable")
        }
        FraudFeatureNames.SENDER_RECIPIENT_PRIOR_SEND_COUNT -> {
            store.senderRecipientPriorSendCount(
                senderId = context.event.senderId,
                recipientId = context.event.recipientId,
                asOf = context.event.timestamp,
            )?.let { FeatureValue.NumberValue(it) }
                ?: FeatureValue.Unavailable("sender-recipient graph aggregate unavailable")
        }
        else -> FeatureValue.Unavailable("graph feature is not supported: $featureName")
    }
}
