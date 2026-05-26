package com.fraudcontrols.observability

import com.fraudcontrols.core.EventId
import com.fraudcontrols.core.Factor
import com.fraudcontrols.core.ScoreResult
import com.fraudcontrols.scoring.ShadowEvaluation
import com.fraudcontrols.scoring.ShadowScorerRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object ShadowEvaluationContractVersions {
    const val SHADOW_EVALUATION_EVENT = 1
}

fun List<ShadowEvaluation>.toShadowEvaluationEventJsonString(): String {
    require(isNotEmpty()) { "shadow evaluation event must include at least one evaluation" }
    val eventId = first().eventId
    require(all { it.eventId == eventId }) { "shadow evaluation event must contain one event_id" }
    return buildJsonObject {
        put("schema_version", ShadowEvaluationContractVersions.SHADOW_EVALUATION_EVENT)
        put("event_id", eventId.value)
        put("evaluations", JsonArray(map { it.toJsonObject() }))
    }.toString()
}

fun parseShadowEvaluationEventContract(payload: String): List<ShadowEvaluation> {
    val json = Json.parseToJsonElement(payload).jsonObject
    val version = json["schema_version"]?.jsonPrimitive?.intOrNull
        ?: error("shadow evaluation event payload is missing schema_version")
    require(version == ShadowEvaluationContractVersions.SHADOW_EVALUATION_EVENT) {
        "shadow evaluation event schema_version $version is not supported by this reader; " +
            "expected ${ShadowEvaluationContractVersions.SHADOW_EVALUATION_EVENT}"
    }
    val eventId = EventId(json.requiredString("event_id"))
    val evaluations = json["evaluations"]?.jsonArray
        ?: error("shadow evaluation event payload is missing evaluations")
    require(evaluations.isNotEmpty()) { "shadow evaluation event must include at least one evaluation" }
    return evaluations.map { element ->
        val evaluation = element.jsonObject
        ShadowEvaluation(
            eventId = eventId,
            scorerName = evaluation.requiredString("scorer_name"),
            scorerVersion = evaluation.requiredString("scorer_version"),
            role = ShadowScorerRole.valueOf(evaluation.requiredString("role")),
            result = evaluation["score"]?.jsonObject?.toScoreResult(),
            error = evaluation["error"]?.jsonPrimitive?.content,
        )
    }
}

private fun ShadowEvaluation.toJsonObject(): JsonObject =
    buildJsonObject {
        put("scorer_name", scorerName)
        put("scorer_version", scorerVersion)
        put("role", role.name)
        result?.let { put("score", it.toJsonObject()) }
        error?.let { put("error", it) }
    }

private fun ScoreResult.toJsonObject(): JsonObject =
    buildJsonObject {
        put("score", score)
        rawScore?.let { put("raw_score", it) }
        put("contributing_factors", JsonArray(contributingFactors.map { it.toJsonObject() }))
        put("model_version", modelVersion)
        put("latency_ms", latencyMs)
        put("degraded", degraded)
    }

private fun JsonObject.toScoreResult(): ScoreResult =
    ScoreResult(
        score = requiredDouble("score"),
        rawScore = this["raw_score"]?.jsonPrimitive?.doubleOrNull,
        contributingFactors = this["contributing_factors"]?.jsonArray.orEmpty().map { element ->
            val factor = element.jsonObject
            Factor(
                name = factor.requiredString("name"),
                contribution = factor.requiredDouble("contribution"),
            )
        },
        modelVersion = requiredString("model_version"),
        latencyMs = requiredDouble("latency_ms"),
        degraded = this["degraded"]?.jsonPrimitive?.boolean ?: false,
    )

private fun Factor.toJsonObject(): JsonObject =
    buildJsonObject {
        put("name", name)
        put("contribution", contribution)
    }

private fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.content ?: error("missing required field: $name")

private fun JsonObject.requiredDouble(name: String): Double =
    this[name]?.jsonPrimitive?.double ?: error("missing required field: $name")
