package com.fraudcontrols.streaming

import com.fraudcontrols.core.Decision
import com.fraudcontrols.decisioning.DecisionPublisher
import com.fraudcontrols.decisioning.RuleEvaluationPublisher
import com.fraudcontrols.rules.RuleEvaluationResult
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import java.time.Duration
import java.util.Properties
import java.util.concurrent.TimeUnit

class KafkaDecisionPublisher(
    private val producer: Producer<String, String>,
    private val decisionsTopic: String,
    private val sendTimeout: Duration = Duration.ofSeconds(5),
) : DecisionPublisher {
    override suspend fun publish(decision: Decision) {
        producer.send(
            ProducerRecord(decisionsTopic, decision.eventId.value, decision.toDecisionJson()),
        ).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS)
    }
}

class KafkaRuleEvaluationPublisher(
    private val producer: Producer<String, String>,
    private val ruleEvaluationsTopic: String,
    private val sendTimeout: Duration = Duration.ofSeconds(5),
) : RuleEvaluationPublisher {
    override suspend fun publish(evaluation: RuleEvaluationResult) {
        producer.send(
            ProducerRecord(ruleEvaluationsTopic, evaluation.eventId.value, evaluation.toRuleEvaluationJson()),
        ).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS)
    }
}

fun kafkaStringProducer(bootstrapServers: String): KafkaProducer<String, String> = KafkaProducer(
    Properties().apply {
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.ACKS_CONFIG, "all")
        put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true")
    },
)
