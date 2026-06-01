package com.fraudcontrols.streaming

import com.fraudcontrols.core.Decision
import com.fraudcontrols.core.DecisionAction
import com.fraudcontrols.core.EventId
import com.fraudcontrols.core.Factor
import com.fraudcontrols.core.FeatureSnapshot
import com.fraudcontrols.core.FeatureValue
import com.fraudcontrols.core.ReasonCode
import com.fraudcontrols.core.ScoreResult
import com.fraudcontrols.decisioning.DecisionAuditRowSink
import com.fraudcontrols.decisioning.DecisionRecord
import com.fraudcontrols.decisioning.DecisioningResult
import com.fraudcontrols.decisioning.contracts.DecisionAuditRowContract
import com.fraudcontrols.decisioning.contracts.parseDecisionSideEffectEnvelopeContract
import com.fraudcontrols.rules.ResolvedRuleAction
import com.fraudcontrols.rules.RuleAction
import com.fraudcontrols.rules.RuleActionType
import com.fraudcontrols.rules.RuleEvaluationConditionResult
import com.fraudcontrols.rules.RuleEvaluationDetail
import com.fraudcontrols.rules.RuleEvaluationResult
import com.fraudcontrols.rules.RuleMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.serialization.StringSerializer
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class KafkaDecisionSideEffectsTest {
    @Test
    fun `outbox publishes a durable side effect envelope`() = runBlocking {
        val producer = MockProducer(true, StringSerializer(), StringSerializer())
        val outbox = KafkaDecisionSideEffectOutbox(producer, "decision_side_effects")

        outbox.record(sampleResult())

        val record = producer.history().single()
        assertEquals("decision_side_effects", record.topic())
        assertEquals("evt-1", record.key())
        val envelope = parseDecisionSideEffectEnvelopeContract(record.value())
        assertEquals("evt-1", envelope.eventId)
        assertEquals("HOLD", Json.parseToJsonElement(envelope.decisionJson).jsonObject["action"]?.jsonPrimitive?.content)
        assertEquals("fixed-v1", envelope.auditRow.modelVersion)
    }

    @Test
    fun `executor applies audit and output side effects from the envelope`() = runBlocking {
        val producer = MockProducer(true, StringSerializer(), StringSerializer())
        KafkaDecisionSideEffectOutbox(producer, "decision_side_effects").record(sampleResult())
        val envelope = parseDecisionSideEffectEnvelopeContract(producer.history().single().value())
        val auditSink = RecordingAuditRowSink()
        val decisionPublisher = RecordingRawEventPublisher()
        val ruleEvaluationPublisher = RecordingRawEventPublisher()
        val executor = DecisionSideEffectExecutor(
            auditSink = auditSink,
            decisionPublisher = decisionPublisher,
            ruleEvaluationPublisher = ruleEvaluationPublisher,
        )

        executor.execute(envelope)

        assertEquals(listOf("evt-1"), auditSink.rows.map { it.eventId })
        assertEquals(listOf("evt-1" to envelope.ruleEvaluationJson), ruleEvaluationPublisher.records)
        assertEquals(listOf("evt-1" to envelope.decisionJson), decisionPublisher.records)
    }

    private fun sampleResult(): DecisioningResult {
        val score = ScoreResult(
            score = 0.42,
            rawScore = 0.5,
            contributingFactors = listOf(Factor(name = "fixed", contribution = 0.42)),
            modelVersion = "fixed-v1",
            latencyMs = 1.5,
        )
        val decision = Decision(
            eventId = EventId("evt-1"),
            action = DecisionAction.HOLD,
            reasonCodes = listOf(ReasonCode("velocity_spike")),
            score = score,
            ruleEvaluationIds = listOf("velocity-spike"),
            decidedAt = Instant.parse("2026-01-01T12:00:05Z"),
        )
        val features = FeatureSnapshot(
            eventId = EventId("evt-1"),
            values = linkedMapOf(
                "amount" to FeatureValue.NumberValue(25.50),
                "fraud_model_score" to FeatureValue.ScoreValue(score),
            ),
        )
        val action = RuleAction(
            type = RuleActionType.REVIEW_QUEUE,
            reasonCode = ReasonCode("velocity_spike"),
            queue = "trust_safety_l2",
        )
        val ruleEvaluation = RuleEvaluationResult(
            eventId = EventId("evt-1"),
            evaluations = listOf(
                RuleEvaluationDetail(
                    ruleId = "velocity-spike",
                    ruleVersion = 1,
                    mode = RuleMode.ENFORCE,
                    priority = 100,
                    conditionResult = RuleEvaluationConditionResult.MATCHED,
                    action = action,
                    featureValues = features.values,
                ),
            ),
            resolvedAction = ResolvedRuleAction(
                ruleId = "velocity-spike",
                ruleVersion = 1,
                decisionAction = DecisionAction.HOLD,
                action = action,
                priority = 100,
            ),
        )
        val record = DecisionRecord(
            decision = decision,
            features = features,
            ruleEvaluation = ruleEvaluation,
            score = score,
        )
        return DecisioningResult(
            decision = decision,
            features = features,
            ruleEvaluation = ruleEvaluation,
            record = record,
        )
    }
}

private class RecordingAuditRowSink : DecisionAuditRowSink {
    val rows = mutableListOf<DecisionAuditRowContract>()

    override suspend fun record(row: DecisionAuditRowContract) {
        rows += row
    }
}

private class RecordingRawEventPublisher : RawEventPublisher {
    val records = mutableListOf<Pair<String, String>>()

    override suspend fun publish(key: String, payload: String) {
        records += key to payload
    }
}
