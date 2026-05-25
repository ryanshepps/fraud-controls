package com.fraudcontrols.features

import com.fraudcontrols.core.CustomerId
import com.fraudcontrols.core.DeviceFingerprint
import com.fraudcontrols.core.FeatureValue
import com.fraudcontrols.core.GeoPoint
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
    override suspend fun compute(context: ScoringContext): FeatureValue =
        when (featureName) {
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

interface DeviceGeoFeatureStore {
    suspend fun deviceDistinctAccounts30d(deviceFingerprint: DeviceFingerprint, asOf: Instant): Double?
    suspend fun impossibleTravelFromLastLogin(
        customerId: CustomerId,
        currentGeo: GeoPoint,
        at: Instant,
    ): Boolean?
}

class DeviceGeoFeatureProvider(
    override val featureName: String,
    private val store: DeviceGeoFeatureStore,
) : FeatureProvider {
    override suspend fun compute(context: ScoringContext): FeatureValue =
        when (featureName) {
            FraudFeatureNames.DEVICE_DISTINCT_ACCOUNTS_30D -> {
                store.deviceDistinctAccounts30d(context.event.senderDeviceFingerprint, context.event.timestamp)
                    ?.let { FeatureValue.NumberValue(it) }
                    ?: FeatureValue.Unavailable("device aggregate unavailable")
            }
            FraudFeatureNames.IMPOSSIBLE_TRAVEL_FROM_LAST_LOGIN -> {
                store.impossibleTravelFromLastLogin(
                    customerId = context.event.senderId,
                    currentGeo = context.event.senderGeo,
                    at = context.event.timestamp,
                )?.let { FeatureValue.BooleanValue(it) }
                    ?: FeatureValue.Unavailable("last-login geo state unavailable")
            }
            else -> FeatureValue.Unavailable("device/geo feature is not supported: $featureName")
        }
}

fun interface FraudModelScoreSource {
    suspend fun score(context: ScoringContext): Double
}

class FraudModelScoreFeatureProvider(
    private val scoreSource: FraudModelScoreSource,
) : FeatureProvider {
    override val featureName: String = FraudFeatureNames.FRAUD_MODEL_SCORE

    override suspend fun compute(context: ScoringContext): FeatureValue =
        FeatureValue.NumberValue(scoreSource.score(context))
}

fun defaultVelocityFeatureProviders(store: VelocityFeatureStore): List<FeatureProvider> =
    listOf(
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
