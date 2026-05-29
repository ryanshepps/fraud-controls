package com.fraudcontrols.observability

import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import java.time.Duration
import java.util.Properties

class KafkaShadowEvaluationReporterConsumer(
    private val consumer: Consumer<String, String>,
    private val reporter: ShadowEvaluationReporter,
    private val shadowEvaluationsTopic: String = DEFAULT_SHADOW_EVALUATIONS_TOPIC,
    private val ruleEvaluationsTopic: String = DEFAULT_RULE_EVALUATIONS_TOPIC,
) : AutoCloseable {
    fun pollAndReport(timeout: Duration): Int {
        val records = consumer.poll(timeout)
        for (record in records) {
            when (record.topic()) {
                shadowEvaluationsTopic -> reporter.recordShadowEvaluationPayload(record.value())
                ruleEvaluationsTopic -> reporter.recordRuleEvaluationPayload(record.value())
            }
        }
        if (!records.isEmpty) {
            consumer.commitSync()
        }
        return records.count()
    }

    override fun close() {
        consumer.close()
    }
}

fun kafkaShadowReporterConsumer(
    bootstrapServers: String,
    groupId: String,
    topics: List<String> = listOf(DEFAULT_SHADOW_EVALUATIONS_TOPIC, DEFAULT_RULE_EVALUATIONS_TOPIC),
): Consumer<String, String> {
    val props = Properties()
    props[ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG] = bootstrapServers
    props[ConsumerConfig.GROUP_ID_CONFIG] = groupId
    props[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java.name
    props[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java.name
    props[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
    props[ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG] = "false"
    return KafkaConsumer<String, String>(props).also { it.subscribe(topics) }
}
