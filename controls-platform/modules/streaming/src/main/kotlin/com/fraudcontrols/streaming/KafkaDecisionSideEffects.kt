package com.fraudcontrols.streaming

import com.fraudcontrols.decisioning.DecisionAuditRowSink
import com.fraudcontrols.decisioning.DecisionSideEffectSink
import com.fraudcontrols.decisioning.DecisioningResult
import com.fraudcontrols.decisioning.contracts.DecisionSideEffectEnvelopeContract
import com.fraudcontrols.decisioning.contracts.parseDecisionSideEffectEnvelopeContract
import com.fraudcontrols.decisioning.contracts.toDecisionSideEffectEnvelopeJsonString
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import java.time.Duration
import java.util.Properties
import java.util.concurrent.TimeUnit

class KafkaDecisionSideEffectOutbox(
    private val producer: Producer<String, String>,
    private val topic: String,
    private val sendTimeout: Duration = Duration.ofSeconds(5),
) : DecisionSideEffectSink {
    override suspend fun record(result: DecisioningResult) {
        producer.send(
            ProducerRecord(
                topic,
                result.decision.eventId.value,
                result.toDecisionSideEffectEnvelopeJsonString(),
            ),
        ).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS)
    }
}

class DecisionSideEffectExecutor(
    private val auditSink: DecisionAuditRowSink,
    private val decisionPublisher: RawEventPublisher,
    private val ruleEvaluationPublisher: RawEventPublisher,
) {
    suspend fun execute(envelope: DecisionSideEffectEnvelopeContract) {
        auditSink.record(envelope.auditRow)
        ruleEvaluationPublisher.publish(envelope.eventId, envelope.ruleEvaluationJson)
        decisionPublisher.publish(envelope.eventId, envelope.decisionJson)
    }
}

interface RawEventPublisher {
    suspend fun publish(key: String, payload: String)
}

class KafkaRawEventPublisher(
    private val producer: Producer<String, String>,
    private val topic: String,
    private val sendTimeout: Duration = Duration.ofSeconds(5),
) : RawEventPublisher {
    override suspend fun publish(key: String, payload: String) {
        producer.send(ProducerRecord(topic, key, payload)).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS)
    }
}

class KafkaDecisionSideEffectConsumer(
    private val consumer: Consumer<String, String>,
    private val executor: DecisionSideEffectExecutor,
) : AutoCloseable {
    suspend fun pollAndExecute(timeout: Duration): Int {
        val records = consumer.poll(timeout)
        var handled = 0
        for (record in records) {
            try {
                executor.execute(parseDecisionSideEffectEnvelopeContract(record.value()))
                handled += 1
            } catch (error: Exception) {
                consumer.seek(TopicPartition(record.topic(), record.partition()), record.offset())
                throw error
            }
        }
        if (!records.isEmpty) {
            consumer.commitSync()
        }
        return handled
    }

    override fun close() {
        consumer.close()
    }
}

fun kafkaDecisionSideEffectConsumer(
    bootstrapServers: String,
    groupId: String,
    topics: Collection<String>,
): KafkaConsumer<String, String> = KafkaConsumer<String, String>(
    Properties().apply {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ConsumerConfig.GROUP_ID_CONFIG, groupId)
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
    },
).also { it.subscribe(topics) }
