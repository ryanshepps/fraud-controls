package com.fraudcontrols.persistence

import com.fraudcontrols.core.CustomerId
import com.fraudcontrols.features.VelocityFeatureStore
import com.fraudcontrols.features.VelocityMetric
import com.fraudcontrols.features.VelocityWindow
import java.math.BigDecimal
import java.time.Instant
import redis.clients.jedis.JedisPooled

class RedisVelocityFeatureStore(
    private val redis: JedisPooled,
) : VelocityFeatureStore {
    override suspend fun senderVelocity(
        senderId: CustomerId,
        metric: VelocityMetric,
        window: VelocityWindow,
        asOf: Instant,
    ): Double? {
        val sinceMillis = asOf.minus(window.duration).toEpochMilli()
        val untilMillis = asOf.toEpochMilli()
        val events = redis.zrangeByScore(senderKey(senderId), sinceMillis.toDouble(), untilMillis.toDouble())
        return when (metric) {
            VelocityMetric.SEND_COUNT -> events.size.toDouble()
            VelocityMetric.SEND_AMOUNT_SUM -> events.sumOf { it.substringAfterLast(':').toDouble() }
        }
    }

    fun recordSenderSend(
        senderId: CustomerId,
        eventId: String,
        occurredAt: Instant,
        amount: BigDecimal,
    ) {
        val member = "${occurredAt.toEpochMilli()}:$eventId:$amount"
        redis.zadd(senderKey(senderId), occurredAt.toEpochMilli().toDouble(), member)
    }

    fun trimSenderSendsBefore(
        senderId: CustomerId,
        before: Instant,
    ) {
        redis.zremrangeByScore(senderKey(senderId), Double.NEGATIVE_INFINITY, before.toEpochMilli().toDouble())
    }

    private fun senderKey(senderId: CustomerId): String =
        "velocity:sender:${senderId.value}:sends"
}
