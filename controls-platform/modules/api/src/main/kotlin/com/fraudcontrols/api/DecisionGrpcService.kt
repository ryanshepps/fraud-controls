package com.fraudcontrols.api

import com.fraudcontrols.core.Decision
import com.fraudcontrols.core.DecisionAction
import com.fraudcontrols.core.EventId
import com.fraudcontrols.decisioning.DecisionProcessor
import com.fraudcontrols.decisioning.DecisionRecord as DomainDecisionRecord
import com.fraudcontrols.decisioning.contracts.toDecisionAuditRowContract
import com.fraudcontrols.decisioning.v1.DecisionAction as ProtoDecisionAction
import com.fraudcontrols.decisioning.v1.DecisionRecord as ProtoDecisionRecord
import com.fraudcontrols.decisioning.v1.DecisionServiceGrpcKt
import com.fraudcontrols.decisioning.v1.EvaluateRequest
import com.fraudcontrols.decisioning.v1.EvaluateResponse
import com.fraudcontrols.decisioning.v1.GetDecisionRequest
import com.fraudcontrols.rules.RuleDefinition
import com.fraudcontrols.streaming.FraudgenEventParseException
import com.fraudcontrols.streaming.FraudgenEventParser
import io.grpc.Server
import io.grpc.Status
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant

class GrpcDecisionService(
    private val processor: DecisionProcessor,
    private val ruleSource: suspend () -> List<RuleDefinition>,
    private val decisionRecords: DecisionRecordReader,
    private val parser: FraudgenEventParser = FraudgenEventParser(),
    private val clock: Clock = Clock.systemUTC(),
) : DecisionServiceGrpcKt.DecisionServiceCoroutineImplBase() {
    override suspend fun evaluate(request: EvaluateRequest): EvaluateResponse {
        val payload = request.transactionJson
        if (payload.isBlank()) {
            throw Status.INVALID_ARGUMENT
                .withDescription("transaction_json is required")
                .asRuntimeException()
        }

        val event = try {
            parser.parse(payload)
        } catch (error: FraudgenEventParseException) {
            throw Status.INVALID_ARGUMENT
                .withDescription(error.message)
                .asRuntimeException()
        }

        return processor.process(
            event = event,
            rules = ruleSource(),
            decidedAt = Instant.now(clock),
        ).decision.toEvaluateResponse()
    }

    override suspend fun getDecision(request: GetDecisionRequest): ProtoDecisionRecord {
        val eventId = request.eventId.trim()
        if (eventId.isBlank()) {
            throw Status.INVALID_ARGUMENT
                .withDescription("event_id is required")
                .asRuntimeException()
        }

        val record = decisionRecords.find(EventId(eventId))
            ?: throw Status.NOT_FOUND
                .withDescription("decision not found: $eventId")
                .asRuntimeException()
        return record.toProto()
    }
}

fun startDecisionGrpcServer(
    service: GrpcDecisionService,
    host: String = "127.0.0.1",
    port: Int = 9091,
): Server =
    NettyServerBuilder
        .forAddress(InetSocketAddress(host, port))
        .addService(service)
        .build()
        .start()

private fun Decision.toEvaluateResponse(): EvaluateResponse =
    EvaluateResponse.newBuilder()
        .setEventId(eventId.value)
        .setAction(action.toProto())
        .addAllReasonCodes(reasonCodes.map { it.value })
        .setScore(score.score)
        .setDecidedAt(decidedAt.toString())
        .build()

private fun DomainDecisionRecord.toProto(): ProtoDecisionRecord {
    val row = toDecisionAuditRowContract()
    return ProtoDecisionRecord.newBuilder()
        .setSchemaVersion(row.schemaVersion)
        .setEventId(row.eventId)
        .setDecidedAt(row.decidedAt)
        .setAction(decision.action.toProto())
        .addAllReasonCodes(row.reasonCodes)
        .setScore(row.score)
        .setModelVersion(row.modelVersion)
        .setScoreJson(row.scoreJson)
        .addAllRuleEvaluationIds(row.ruleEvaluationIds)
        .setFeaturesJson(row.featuresJson)
        .setRuleEvaluationJson(row.ruleEvaluationJson)
        .build()
}

private fun DecisionAction.toProto(): ProtoDecisionAction =
    when (this) {
        DecisionAction.ALLOW -> ProtoDecisionAction.DECISION_ACTION_ALLOW
        DecisionAction.CHALLENGE -> ProtoDecisionAction.DECISION_ACTION_CHALLENGE
        DecisionAction.HOLD -> ProtoDecisionAction.DECISION_ACTION_HOLD
        DecisionAction.DENY -> ProtoDecisionAction.DECISION_ACTION_DENY
    }
