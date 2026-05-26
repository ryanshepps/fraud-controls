package com.fraudcontrols.observability

import com.fraudcontrols.scoring.ShadowEvaluation
import com.fraudcontrols.scoring.ShadowEvaluationSink
import java.time.Duration
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord

class KafkaShadowEvaluationSink(
    private val producer: Producer<String, String>,
    private val topic: String = DEFAULT_SHADOW_EVALUATIONS_TOPIC,
    private val sendTimeout: Duration = Duration.ofSeconds(5),
) : ShadowEvaluationSink {
    override suspend fun record(evaluations: List<ShadowEvaluation>) {
        if (evaluations.isEmpty()) {
            return
        }
        val eventId = evaluations.first().eventId.value
        producer.send(
            ProducerRecord(
                topic,
                eventId,
                evaluations.toShadowEvaluationEventJsonString(),
            ),
        ).get(sendTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
    }
}

const val DEFAULT_SHADOW_EVALUATIONS_TOPIC = "shadow_evaluations"
const val DEFAULT_RULE_EVALUATIONS_TOPIC = "rule_evaluations"
