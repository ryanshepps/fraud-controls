package com.fraudcontrols.observability

import com.fraudcontrols.core.EventId
import com.fraudcontrols.core.Factor
import com.fraudcontrols.core.ScoreResult
import com.fraudcontrols.scoring.ShadowEvaluation
import com.fraudcontrols.scoring.ShadowScorerRole
import java.time.Duration
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.MockConsumer
import org.apache.kafka.clients.consumer.OffsetResetStrategy
import org.apache.kafka.common.TopicPartition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KafkaShadowEvaluationReporterConsumerTest {
    @Test
    fun `polls shadow and rule evaluation topics into reporter metrics`() {
        val metrics = MicrometerControlsMetrics()
        val reporter = ShadowEvaluationReporter(metrics, threshold = 0.5)
        val consumer = MockConsumer<String, String>(OffsetResetStrategy.EARLIEST)
        val shadowPartition = TopicPartition(DEFAULT_SHADOW_EVALUATIONS_TOPIC, 0)
        val rulePartition = TopicPartition(DEFAULT_RULE_EVALUATIONS_TOPIC, 0)
        consumer.assign(listOf(shadowPartition, rulePartition))
        consumer.updateBeginningOffsets(mapOf(shadowPartition to 0L, rulePartition to 0L))
        consumer.addRecord(
            ConsumerRecord(
                DEFAULT_SHADOW_EVALUATIONS_TOPIC,
                0,
                0L,
                "evt-1",
                listOf(
                    shadowEvaluation("primary", ShadowScorerRole.PRIMARY, 0.2),
                    shadowEvaluation("candidate", ShadowScorerRole.SHADOW, 0.9),
                ).toShadowEvaluationEventJsonString(),
            ),
        )
        consumer.addRecord(
            ConsumerRecord(
                DEFAULT_RULE_EVALUATIONS_TOPIC,
                0,
                0L,
                "evt-1",
                """
                {
                  "schema_version": 2,
                  "event_id": "evt-1",
                  "matches": [
                    {
                      "rule_id": "shadow-review",
                      "rule_version": 1,
                      "mode": "SHADOW",
                      "priority": 100,
                      "action_type": "REVIEW_QUEUE"
                    }
                  ],
                  "skipped": [],
                  "resolved_action": {
                    "rule_id": "enforce-review",
                    "rule_version": 1,
                    "decision_action": "HOLD",
                    "priority": 100,
                    "action_type": "REVIEW_QUEUE"
                  }
                }
                """.trimIndent(),
            ),
        )
        val reporterConsumer = KafkaShadowEvaluationReporterConsumer(consumer, reporter)

        val processed = reporterConsumer.pollAndReport(Duration.ZERO)

        assertEquals(2, processed)
        val scrape = metrics.scrape()
        assertTrue(scrape.contains("controls_scorer_decision_flip_rate{primary_scorer=\"primary\",shadow_scorer=\"candidate\"} 1.0"))
        assertTrue(scrape.contains("controls_shadow_rule_would_have_blocked_rate{rule_id=\"shadow-review\"} 1.0"))
    }

    private fun shadowEvaluation(
        scorerName: String,
        role: ShadowScorerRole,
        score: Double,
    ): ShadowEvaluation =
        ShadowEvaluation(
            eventId = EventId("evt-1"),
            scorerName = scorerName,
            scorerVersion = "v1",
            role = role,
            result = ScoreResult(
                score = score,
                rawScore = null,
                contributingFactors = listOf(Factor("fixed", score)),
                modelVersion = "v1",
                latencyMs = 1.0,
            ),
            error = null,
        )
}
