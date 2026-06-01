package com.fraudcontrols.streaming

import com.fraudcontrols.core.TransactionEvent
import com.fraudcontrols.decisioning.DecisionProcessor
import com.fraudcontrols.rules.RuleDefinition
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import java.time.Clock
import java.time.Duration
import java.util.Properties

class KafkaTransactionDecisionConsumer(
    private val consumer: Consumer<String, String>,
    private val processor: DecisionProcessor,
    private val rules: () -> List<RuleDefinition>,
    private val parser: FraudgenEventParser = FraudgenEventParser(),
    private val clock: Clock = Clock.systemUTC(),
    private val beforeProcess: suspend (TransactionEvent) -> Unit = {},
) : AutoCloseable {
    suspend fun pollAndProcess(timeout: Duration): Int {
        val records = consumer.poll(timeout)
        for (record in records) {
            try {
                val event = parser.parse(record.value())
                beforeProcess(event)
                processor.process(event = event, rules = rules(), decidedAt = clock.instant())
            } catch (error: Exception) {
                consumer.seek(TopicPartition(record.topic(), record.partition()), record.offset())
                throw error
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

fun kafkaStringConsumer(
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
