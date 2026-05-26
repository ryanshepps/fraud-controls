package com.fraudcontrols.persistence

import com.fraudcontrols.core.FeatureSnapshot
import com.fraudcontrols.core.FeatureValue
import com.fraudcontrols.core.ScoreResult
import com.fraudcontrols.decisioning.DecisionAuditSink
import com.fraudcontrols.decisioning.DecisionRecord
import com.fraudcontrols.rules.RuleEvaluationResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest

class DynamoDecisionAuditSink(
    private val dynamoDb: DynamoDbClient,
    private val tableName: String,
) : DecisionAuditSink {
    override suspend fun record(record: DecisionRecord) {
        dynamoDb.putItem(
            PutItemRequest.builder()
                .tableName(tableName)
                .item(record.toItem())
                .build(),
        )
    }
}

private fun DecisionRecord.toItem(): Map<String, AttributeValue> =
    mapOf(
        "event_id" to s(decision.eventId.value),
        "decided_at" to s(decision.decidedAt.toString()),
        "action" to s(decision.action.name),
        "reason_codes" to stringList(decision.reasonCodes.map { it.value }),
        "score" to n(score.score),
        "model_version" to s(score.modelVersion),
        "score_json" to s(score.toJsonString()),
        "rule_evaluation_ids" to stringList(decision.ruleEvaluationIds),
        "features_json" to s(features.toJsonString()),
        "rule_evaluation_json" to s(ruleEvaluation.toJsonString()),
    )

private fun ScoreResult.toJsonString(): String =
    buildJsonObject {
        put("score", score)
        rawScore?.let { put("raw_score", it) }
        put(
            "contributing_factors",
            JsonArray(
                contributingFactors.map { factor ->
                    buildJsonObject {
                        put("name", factor.name)
                        put("contribution", factor.contribution)
                    }
                },
            ),
        )
        put("model_version", modelVersion)
        put("latency_ms", latencyMs)
        put("degraded", degraded)
    }.toString()

private fun FeatureSnapshot.toJsonString(): String =
    buildJsonObject {
        put("event_id", eventId.value)
        put(
            "values",
            JsonObject(values.mapValues { (_, value) -> value.toJsonValue() }),
        )
    }.toString()

private fun FeatureValue.toJsonValue(): JsonObject =
    when (this) {
        is FeatureValue.BooleanValue -> typedValue("boolean", JsonPrimitive(value))
        is FeatureValue.Missing -> typedValue("missing", JsonPrimitive(reason))
        is FeatureValue.NumberValue -> typedValue("number", JsonPrimitive(value))
        is FeatureValue.ScoreValue -> typedValue("score", JsonPrimitive(value))
        is FeatureValue.SetValue -> typedValue("set", JsonArray(values.map(::JsonPrimitive)))
        is FeatureValue.TextValue -> typedValue("text", JsonPrimitive(value))
        is FeatureValue.Unavailable -> typedValue("unavailable", JsonPrimitive(reason))
    }

private fun typedValue(
    type: String,
    value: kotlinx.serialization.json.JsonElement,
): JsonObject =
    buildJsonObject {
        put("type", type)
        put("value", value)
    }

private fun RuleEvaluationResult.toJsonString(): String =
    buildJsonObject {
        put("event_id", eventId.value)
        put("matches", JsonArray(matches.map { JsonPrimitive(it.ruleId) }))
        put("skipped", JsonArray(skipped.map { JsonPrimitive(it.ruleId) }))
        resolvedAction?.let { put("resolved_action", it.ruleId) }
    }.toString()

private fun s(value: String): AttributeValue =
    AttributeValue.builder().s(value).build()

private fun n(value: Double): AttributeValue =
    AttributeValue.builder().n(value.toString()).build()

private fun stringList(values: List<String>): AttributeValue =
    AttributeValue.builder().l(values.map(::s)).build()
