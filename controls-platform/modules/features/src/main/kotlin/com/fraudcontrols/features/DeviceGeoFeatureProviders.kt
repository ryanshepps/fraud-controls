package com.fraudcontrols.features

import com.fraudcontrols.core.CustomerId
import com.fraudcontrols.core.DeviceFingerprint
import com.fraudcontrols.core.FeatureValue
import com.fraudcontrols.core.GeoPoint
import com.fraudcontrols.core.ScoringContext
import java.time.Instant

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
    override suspend fun compute(context: ScoringContext): FeatureValue = when (featureName) {
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
