package com.fraudcontrols.streaming

import com.fraudcontrols.core.Decision
import com.fraudcontrols.core.ScoreResult
import com.fraudcontrols.rules.ResolvedRuleAction
import com.fraudcontrols.rules.RuleEvaluationResult
import com.fraudcontrols.rules.RuleMatch
import com.fraudcontrols.rules.SkippedRule
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun Decision.toDecisionJson(): String =
    buildJsonObject {
        put("event_id", eventId.value)
        put("action", action.name)
        put("reason_codes", reasonCodes.map { it.value }.toJsonArray())
        put("score", score.toJsonObject())
        put("rule_evaluation_ids", ruleEvaluationIds.toJsonArray())
        put("decided_at", decidedAt.toString())
    }.toString()

fun RuleEvaluationResult.toRuleEvaluationJson(): String =
    buildJsonObject {
        put("event_id", eventId.value)
        put("matches", JsonArray(matches.map { it.toJsonObject() }))
        put("skipped", JsonArray(skipped.map { it.toJsonObject() }))
        resolvedAction?.let { put("resolved_action", it.toJsonObject()) }
    }.toString()

private fun ScoreResult.toJsonObject(): JsonObject =
    buildJsonObject {
        put("score", score)
        rawScore?.let { put("raw_score", it) }
        put(
            "contributing_factors",
            buildJsonArray {
                contributingFactors.forEach { factor ->
                    add(
                        buildJsonObject {
                            put("name", factor.name)
                            put("contribution", factor.contribution)
                        },
                    )
                }
            },
        )
        put("model_version", modelVersion)
        put("latency_ms", latencyMs)
        put("degraded", degraded)
    }

private fun RuleMatch.toJsonObject(): JsonObject =
    buildJsonObject {
        put("rule_id", ruleId)
        put("rule_version", ruleVersion)
        put("mode", mode.name)
        put("priority", priority)
        put("action_type", action.type.name)
        action.reasonCode?.let { put("reason_code", it.value) }
    }

private fun SkippedRule.toJsonObject(): JsonObject =
    buildJsonObject {
        put("rule_id", ruleId)
        put("rule_version", ruleVersion)
        put("reason", reason)
    }

private fun ResolvedRuleAction.toJsonObject(): JsonObject =
    buildJsonObject {
        put("rule_id", ruleId)
        put("rule_version", ruleVersion)
        put("decision_action", decisionAction.name)
        put("priority", priority)
        put("action_type", action.type.name)
        action.reasonCode?.let { put("reason_code", it.value) }
    }

private fun Iterable<String>.toJsonArray(): JsonArray =
    JsonArray(map(::JsonPrimitive))
