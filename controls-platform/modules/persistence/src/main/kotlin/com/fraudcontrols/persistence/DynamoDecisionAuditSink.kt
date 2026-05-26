package com.fraudcontrols.persistence

import com.fraudcontrols.decisioning.DecisionAuditSink
import com.fraudcontrols.decisioning.DecisionRecord
import com.fraudcontrols.decisioning.contracts.DecisionAuditRowContract
import com.fraudcontrols.decisioning.contracts.toDecisionAuditRowContract
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
    toDecisionAuditRowContract().toItem()

private fun DecisionAuditRowContract.toItem(): Map<String, AttributeValue> =
    mapOf(
        "event_id" to s(eventId),
        "schema_version" to n(schemaVersion),
        "decided_at" to s(decidedAt),
        "action" to s(action),
        "reason_codes" to stringList(reasonCodes),
        "score" to n(score),
        "model_version" to s(modelVersion),
        "score_json" to s(scoreJson),
        "rule_evaluation_ids" to stringList(ruleEvaluationIds),
        "features_json" to s(featuresJson),
        "rule_evaluation_json" to s(ruleEvaluationJson),
    )

private fun s(value: String): AttributeValue =
    AttributeValue.builder().s(value).build()

private fun n(value: Int): AttributeValue =
    AttributeValue.builder().n(value.toString()).build()

private fun n(value: Double): AttributeValue =
    AttributeValue.builder().n(value.toString()).build()

private fun stringList(values: List<String>): AttributeValue =
    AttributeValue.builder().l(values.map(::s)).build()
