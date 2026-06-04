package com.fraudcontrols.persistence

import com.fraudcontrols.core.EventId
import com.fraudcontrols.decisioning.DecisionAuditRowSink
import com.fraudcontrols.decisioning.DecisionAuditSink
import com.fraudcontrols.decisioning.DecisionRecord
import com.fraudcontrols.decisioning.DecisionRecordReader
import com.fraudcontrols.decisioning.contracts.DecisionAuditRowContract
import com.fraudcontrols.decisioning.contracts.toDecisionAuditRowContract
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest

class DynamoDecisionAuditSink(
    private val dynamoDb: DynamoDbClient,
    private val tableName: String,
) : DecisionAuditSink,
    DecisionAuditRowSink {
    override suspend fun record(record: DecisionRecord) {
        record(record.toDecisionAuditRowContract())
    }

    override suspend fun record(row: DecisionAuditRowContract) {
        dynamoDb.putItem(
            PutItemRequest.builder()
                .tableName(tableName)
                .item(row.toItem())
                .build(),
        )
    }
}

class DynamoDecisionAuditReader(
    private val dynamoDb: DynamoDbClient,
    private val tableName: String,
) : DecisionRecordReader {
    override suspend fun find(eventId: EventId): DecisionAuditRowContract? {
        val item = dynamoDb.getItem(
            GetItemRequest.builder()
                .tableName(tableName)
                .key(mapOf("event_id" to s(eventId.value)))
                .consistentRead(true)
                .build(),
        ).item()

        if (item.isNullOrEmpty()) {
            return null
        }
        return item.toDecisionAuditRowContract()
    }
}

private fun DecisionAuditRowContract.toItem(): Map<String, AttributeValue> = mapOf(
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

private fun s(value: String): AttributeValue = AttributeValue.builder().s(value).build()

private fun n(value: Int): AttributeValue = AttributeValue.builder().n(value.toString()).build()

private fun n(value: Double): AttributeValue = AttributeValue.builder().n(value.toString()).build()

private fun stringList(values: List<String>): AttributeValue = AttributeValue.builder().l(values.map(::s)).build()

private fun Map<String, AttributeValue>.toDecisionAuditRowContract(): DecisionAuditRowContract = DecisionAuditRowContract(
    schemaVersion = requiredInt("schema_version"),
    eventId = requiredString("event_id"),
    decidedAt = requiredString("decided_at"),
    action = requiredString("action"),
    reasonCodes = requiredStringList("reason_codes"),
    score = requiredDouble("score"),
    modelVersion = requiredString("model_version"),
    scoreJson = requiredString("score_json"),
    ruleEvaluationIds = requiredStringList("rule_evaluation_ids"),
    featuresJson = requiredString("features_json"),
    ruleEvaluationJson = requiredString("rule_evaluation_json"),
)

private fun Map<String, AttributeValue>.requiredString(name: String): String = requireNotNull(this[name]?.s()) { "DynamoDB decision audit row missing string attribute $name" }

private fun Map<String, AttributeValue>.requiredInt(name: String): Int = requiredNumber(name).toInt()

private fun Map<String, AttributeValue>.requiredDouble(name: String): Double = requiredNumber(name).toDouble()

private fun Map<String, AttributeValue>.requiredNumber(name: String): String = requireNotNull(this[name]?.n()) { "DynamoDB decision audit row missing number attribute $name" }

private fun Map<String, AttributeValue>.requiredStringList(name: String): List<String> = requireNotNull(this[name]?.l()) { "DynamoDB decision audit row missing list attribute $name" }
    .map { value ->
        requireNotNull(value.s()) { "DynamoDB decision audit row attribute $name contains a non-string value" }
    }
