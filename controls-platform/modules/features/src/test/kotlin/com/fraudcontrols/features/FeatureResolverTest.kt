package com.fraudcontrols.features

import com.fraudcontrols.core.CustomerId
import com.fraudcontrols.core.DeviceFingerprint
import com.fraudcontrols.core.EventId
import com.fraudcontrols.core.FeatureValue
import com.fraudcontrols.core.GeoPoint
import com.fraudcontrols.core.Money
import com.fraudcontrols.core.ScoringContext
import com.fraudcontrols.core.TransactionEvent
import com.fraudcontrols.core.TransactionType
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class FeatureResolverTest {
    @Test
    fun `resolves event features into a feature snapshot`() = runSuspending {
        val resolver = FeatureResolver(defaultEventFeatureProviders())
        val snapshot = resolver.request(ScoringContext(sampleEvent())).resolveAll(
            listOf(
                FraudFeatureNames.AMOUNT,
                FraudFeatureNames.CURRENCY,
                FraudFeatureNames.TRANSACTION_TYPE,
                FraudFeatureNames.SENDER_BALANCE_DELTA,
                FraudFeatureNames.AMOUNT_TO_SENDER_BALANCE_RATIO,
            ),
        )

        assertEquals(EventId("evt-1"), snapshot.eventId)
        assertEquals(FeatureValue.NumberValue(25.0), snapshot.values[FraudFeatureNames.AMOUNT])
        assertEquals(FeatureValue.TextValue("USD"), snapshot.values[FraudFeatureNames.CURRENCY])
        assertEquals(FeatureValue.TextValue("P2P_SEND"), snapshot.values[FraudFeatureNames.TRANSACTION_TYPE])
        assertEquals(FeatureValue.NumberValue(-25.0), snapshot.values[FraudFeatureNames.SENDER_BALANCE_DELTA])
        assertEquals(
            FeatureValue.NumberValue(0.25),
            snapshot.values[FraudFeatureNames.AMOUNT_TO_SENDER_BALANCE_RATIO],
        )
    }

    @Test
    fun `caches provider results inside one resolution request`() = runSuspending {
        val provider = CountingProvider(
            featureName = "expensive_feature",
            value = FeatureValue.NumberValue(42.0),
        )
        val request = FeatureResolver(listOf(provider)).request(ScoringContext(sampleEvent()))

        assertEquals(FeatureValue.NumberValue(42.0), request.resolve("expensive_feature"))
        assertEquals(FeatureValue.NumberValue(42.0), request.resolve("expensive_feature"))
        assertEquals(1, provider.calls)
    }

    @Test
    fun `returns unavailable for missing or failing providers`() = runSuspending {
        val request = FeatureResolver(
            listOf(
                FailingProvider("broken_feature"),
            ),
        ).request(ScoringContext(sampleEvent()))

        val missing = request.resolve("not_registered")
        val broken = request.resolve("broken_feature")

        assertEquals(
            FeatureValue.Unavailable("no provider registered for feature: not_registered"),
            missing,
        )
        assertContains((broken as FeatureValue.Unavailable).reason, "feature broken_feature failed")
    }

    @Test
    fun `marks sender balance ratio unavailable when denominator is unavailable`() = runSuspending {
        val resolver = FeatureResolver(defaultEventFeatureProviders())
        val event = sampleEvent(
            senderBalanceBefore = BigDecimal("0.00"),
            senderBalanceAfter = BigDecimal("-25.00"),
        )

        val value = resolver.request(ScoringContext(event)).resolve(FraudFeatureNames.AMOUNT_TO_SENDER_BALANCE_RATIO)

        assertEquals(
            FeatureValue.Unavailable("sender balance before is not positive"),
            value,
        )
    }

    private class CountingProvider(
        override val featureName: String,
        private val value: FeatureValue,
    ) : FeatureProvider {
        var calls: Int = 0
            private set

        override suspend fun compute(context: ScoringContext): FeatureValue {
            calls += 1
            return value
        }
    }

    private class FailingProvider(
        override val featureName: String,
    ) : FeatureProvider {
        override suspend fun compute(context: ScoringContext): FeatureValue =
            error("store timeout")
    }
}

class StoreBackedFeatureProviderTest {
    @Test
    fun `resolves velocity features from the velocity store`() = runSuspending {
        val store = RecordingVelocityStore(
            values = mapOf(
                VelocityMetric.SEND_COUNT to 3.0,
                VelocityMetric.SEND_AMOUNT_SUM to 175.25,
            ),
        )
        val providers = defaultVelocityFeatureProviders(store)
        val request = FeatureResolver(providers).request(ScoringContext(sampleEvent()))

        assertEquals(
            FeatureValue.NumberValue(3.0),
            request.resolve(FraudFeatureNames.SENDER_SEND_COUNT_5M),
        )
        assertEquals(
            FeatureValue.NumberValue(175.25),
            request.resolve(FraudFeatureNames.SENDER_SEND_AMOUNT_SUM_1H),
        )
        assertEquals(CustomerId("sender-1"), store.calls.first().senderId)
        assertEquals(VelocityMetric.SEND_COUNT, store.calls.first().metric)
    }

    @Test
    fun `resolves account-state features from the account store`() = runSuspending {
        val store = RecordingAccountStore(
            states = mapOf(
                CustomerId("sender-1") to AccountState(accountAgeDays = 2.0),
                CustomerId("recipient-1") to AccountState(accountAgeDays = 30.0),
            ),
            isNewCounterparty = false,
        )
        val request = FeatureResolver(
            listOf(
                AccountStateFeatureProvider(FraudFeatureNames.SENDER_ACCOUNT_AGE_DAYS, store),
                AccountStateFeatureProvider(FraudFeatureNames.RECIPIENT_ACCOUNT_AGE_DAYS, store),
                AccountStateFeatureProvider(FraudFeatureNames.IS_NEW_COUNTERPARTY, store),
            ),
        ).request(ScoringContext(sampleEvent()))

        assertEquals(FeatureValue.NumberValue(2.0), request.resolve(FraudFeatureNames.SENDER_ACCOUNT_AGE_DAYS))
        assertEquals(FeatureValue.NumberValue(30.0), request.resolve(FraudFeatureNames.RECIPIENT_ACCOUNT_AGE_DAYS))
        assertEquals(FeatureValue.BooleanValue(false), request.resolve(FraudFeatureNames.IS_NEW_COUNTERPARTY))
    }

    @Test
    fun `resolves graph device geo and model score features`() = runSuspending {
        val event = sampleEvent()
        val request = FeatureResolver(
            listOf(
                GraphFeatureProvider(FraudFeatureNames.RECIPIENT_IN_DEGREE_7D, StaticGraphStore),
                GraphFeatureProvider(FraudFeatureNames.SENDER_RECIPIENT_PRIOR_SEND_COUNT, StaticGraphStore),
                DeviceGeoFeatureProvider(FraudFeatureNames.DEVICE_DISTINCT_ACCOUNTS_30D, StaticDeviceGeoStore),
                DeviceGeoFeatureProvider(FraudFeatureNames.IMPOSSIBLE_TRAVEL_FROM_LAST_LOGIN, StaticDeviceGeoStore),
                FraudModelScoreFeatureProvider { FeatureContextAssertions.assertEvent(it, event) },
            ),
        ).request(ScoringContext(event))

        assertEquals(FeatureValue.NumberValue(12.0), request.resolve(FraudFeatureNames.RECIPIENT_IN_DEGREE_7D))
        assertEquals(
            FeatureValue.NumberValue(4.0),
            request.resolve(FraudFeatureNames.SENDER_RECIPIENT_PRIOR_SEND_COUNT),
        )
        assertEquals(
            FeatureValue.NumberValue(6.0),
            request.resolve(FraudFeatureNames.DEVICE_DISTINCT_ACCOUNTS_30D),
        )
        assertEquals(
            FeatureValue.BooleanValue(true),
            request.resolve(FraudFeatureNames.IMPOSSIBLE_TRAVEL_FROM_LAST_LOGIN),
        )
        assertEquals(FeatureValue.NumberValue(0.91), request.resolve(FraudFeatureNames.FRAUD_MODEL_SCORE))
    }

    @Test
    fun `returns unavailable when store-backed state is absent`() = runSuspending {
        val request = FeatureResolver(
            listOf(
                AccountStateFeatureProvider(FraudFeatureNames.SENDER_ACCOUNT_AGE_DAYS, RecordingAccountStore()),
            ),
        ).request(ScoringContext(sampleEvent()))

        assertEquals(
            FeatureValue.Unavailable("account state unavailable for sender-1"),
            request.resolve(FraudFeatureNames.SENDER_ACCOUNT_AGE_DAYS),
        )
    }
}

private data class VelocityCall(
    val senderId: CustomerId,
    val metric: VelocityMetric,
)

private class RecordingVelocityStore(
    private val values: Map<VelocityMetric, Double>,
) : VelocityFeatureStore {
    val calls = mutableListOf<VelocityCall>()

    override suspend fun senderVelocity(
        senderId: CustomerId,
        metric: VelocityMetric,
        window: VelocityWindow,
        asOf: Instant,
    ): Double? {
        calls += VelocityCall(senderId = senderId, metric = metric)
        return values[metric]
    }
}

private class RecordingAccountStore(
    private val states: Map<CustomerId, AccountState> = emptyMap(),
    private val isNewCounterparty: Boolean? = null,
) : AccountStateStore {
    override suspend fun accountState(customerId: CustomerId): AccountState? =
        states[customerId]

    override suspend fun isNewCounterparty(
        senderId: CustomerId,
        recipientId: CustomerId,
    ): Boolean? = isNewCounterparty
}

private object StaticGraphStore : GraphFeatureStore {
    override suspend fun recipientInDegree7d(recipientId: CustomerId, asOf: Instant): Double = 12.0

    override suspend fun senderRecipientPriorSendCount(
        senderId: CustomerId,
        recipientId: CustomerId,
        asOf: Instant,
    ): Double = 4.0
}

private object StaticDeviceGeoStore : DeviceGeoFeatureStore {
    override suspend fun deviceDistinctAccounts30d(
        deviceFingerprint: DeviceFingerprint,
        asOf: Instant,
    ): Double = 6.0

    override suspend fun impossibleTravelFromLastLogin(
        customerId: CustomerId,
        currentGeo: GeoPoint,
        at: Instant,
    ): Boolean = true
}

private object FeatureContextAssertions {
    fun assertEvent(
        context: ScoringContext,
        event: TransactionEvent,
    ): Double {
        assertEquals(event, context.event)
        return 0.91
    }
}

private fun sampleEvent(
    senderBalanceBefore: BigDecimal = BigDecimal("100.00"),
    senderBalanceAfter: BigDecimal = BigDecimal("75.00"),
): TransactionEvent =
    TransactionEvent(
        eventId = EventId("evt-1"),
        timestamp = Instant.parse("2026-01-01T00:00:00Z"),
        senderId = CustomerId("sender-1"),
        recipientId = CustomerId("recipient-1"),
        amount = Money.usd("25.00"),
        transactionType = TransactionType.P2P_SEND,
        senderDeviceFingerprint = DeviceFingerprint("device-1"),
        senderGeo = GeoPoint(latitude = 43.6532, longitude = -79.3832),
        senderBalanceBefore = senderBalanceBefore,
        senderBalanceAfter = senderBalanceAfter,
        recipientBalanceBefore = BigDecimal("50.00"),
        recipientBalanceAfter = BigDecimal("75.00"),
        senderAccountAgeDays = 0.125,
        recipientAccountAgeDays = 120.5,
        isNewCounterparty = true,
    )

private fun <T> runSuspending(block: suspend () -> T): T {
    var value: T? = null
    var error: Throwable? = null
    val completed = CountDownLatch(1)
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                result
                    .onSuccess { value = it }
                    .onFailure { error = it }
                completed.countDown()
            }
        },
    )
    check(completed.await(5, TimeUnit.SECONDS)) { "suspend test did not complete" }
    error?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return value as T
}
