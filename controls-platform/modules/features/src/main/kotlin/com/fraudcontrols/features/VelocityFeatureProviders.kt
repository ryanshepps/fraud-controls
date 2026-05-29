package com.fraudcontrols.features

import com.fraudcontrols.core.CustomerId
import com.fraudcontrols.core.FeatureValue
import com.fraudcontrols.core.ScoringContext
import java.time.Duration
import java.time.Instant

data class VelocityWindow(
    val duration: Duration,
) {
    init {
        require(!duration.isNegative && !duration.isZero) { "velocity window must be positive" }
    }
}

enum class VelocityMetric {
    SEND_COUNT,
    SEND_AMOUNT_SUM,
}

interface VelocityFeatureStore {
    suspend fun senderVelocity(
        senderId: CustomerId,
        metric: VelocityMetric,
        window: VelocityWindow,
        asOf: Instant,
    ): Double?
}

class SenderVelocityFeatureProvider(
    override val featureName: String,
    private val metric: VelocityMetric,
    private val window: VelocityWindow,
    private val store: VelocityFeatureStore,
) : FeatureProvider {
    override suspend fun compute(context: ScoringContext): FeatureValue {
        val value = store.senderVelocity(
            senderId = context.event.senderId,
            metric = metric,
            window = window,
            asOf = context.event.timestamp,
        ) ?: 0.0
        return FeatureValue.NumberValue(value)
    }
}

fun defaultVelocityFeatureProviders(store: VelocityFeatureStore): List<FeatureProvider> = listOf(
    SenderVelocityFeatureProvider(
        featureName = FraudFeatureNames.SENDER_SEND_COUNT_5M,
        metric = VelocityMetric.SEND_COUNT,
        window = VelocityWindow(Duration.ofMinutes(5)),
        store = store,
    ),
    SenderVelocityFeatureProvider(
        featureName = FraudFeatureNames.SENDER_SEND_COUNT_1H,
        metric = VelocityMetric.SEND_COUNT,
        window = VelocityWindow(Duration.ofHours(1)),
        store = store,
    ),
    SenderVelocityFeatureProvider(
        featureName = FraudFeatureNames.SENDER_SEND_COUNT_24H,
        metric = VelocityMetric.SEND_COUNT,
        window = VelocityWindow(Duration.ofHours(24)),
        store = store,
    ),
    SenderVelocityFeatureProvider(
        featureName = FraudFeatureNames.SENDER_SEND_AMOUNT_SUM_5M,
        metric = VelocityMetric.SEND_AMOUNT_SUM,
        window = VelocityWindow(Duration.ofMinutes(5)),
        store = store,
    ),
    SenderVelocityFeatureProvider(
        featureName = FraudFeatureNames.SENDER_SEND_AMOUNT_SUM_1H,
        metric = VelocityMetric.SEND_AMOUNT_SUM,
        window = VelocityWindow(Duration.ofHours(1)),
        store = store,
    ),
    SenderVelocityFeatureProvider(
        featureName = FraudFeatureNames.SENDER_SEND_AMOUNT_SUM_24H,
        metric = VelocityMetric.SEND_AMOUNT_SUM,
        window = VelocityWindow(Duration.ofHours(24)),
        store = store,
    ),
)
