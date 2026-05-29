package com.fraudcontrols.api

import com.fraudcontrols.core.EventId
import com.fraudcontrols.decisioning.DecisionRecordReader
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun Application.installControlsAdminRoutes(
    ruleAdminService: RuleAdminService,
    decisionRecords: DecisionRecordReader,
    metricsPath: String = "/metrics",
    metricsScrape: (() -> String)? = null,
) {
    routing {
        if (metricsScrape != null) {
            get(metricsPath) {
                call.respondText(metricsScrape(), ContentType.Text.Plain)
            }
        }

        get("/rules") {
            val rules = ruleAdminService.list()
            call.respondJson(
                buildJsonObject {
                    put("rules", JsonArray(rules.map { it.toJsonObject() }))
                }.toString(),
            )
        }

        post("/rules") {
            val body = call.receiveText()
            try {
                val created =
                    ruleAdminService.create(
                        rule = parseRuleDefinitionJson(body),
                        actor = parseActor(body),
                    )
                call.respondJson(created.toJsonObject().toString(), HttpStatusCode.Created)
            } catch (error: IllegalArgumentException) {
                call.respondApiError(error)
            }
        }

        put("/rules/{id}") {
            val ruleId = call.parameters["id"].orEmpty()
            val body = call.receiveText()
            try {
                val updated =
                    ruleAdminService.update(
                        ruleId = ruleId,
                        replacement = parseRuleDefinitionJson(body, idOverride = ruleId),
                        actor = parseActor(body),
                    )
                call.respondJson(updated.toJsonObject().toString())
            } catch (error: IllegalArgumentException) {
                call.respondApiError(error)
            }
        }

        post("/rules/{id}/promote") {
            val ruleId = call.parameters["id"].orEmpty()
            val body = call.receiveText()
            try {
                val promoted =
                    ruleAdminService.promote(
                        ruleId = ruleId,
                        confirmed = parsePromotionConfirmation(body),
                        actor = parseActor(body),
                    )
                call.respondJson(promoted.toJsonObject().toString())
            } catch (error: IllegalArgumentException) {
                call.respondApiError(error)
            }
        }

        post("/rules/{id}/disable") {
            val ruleId = call.parameters["id"].orEmpty()
            val body = call.receiveText()
            try {
                val disabled =
                    ruleAdminService.disable(
                        ruleId = ruleId,
                        actor = parseActor(body),
                    )
                call.respondJson(disabled.toJsonObject().toString())
            } catch (error: IllegalArgumentException) {
                call.respondApiError(error)
            }
        }

        get("/rules/{id}/history") {
            val ruleId = call.parameters["id"].orEmpty()
            try {
                val history = ruleAdminService.history(ruleId)
                call.respondJson(
                    buildJsonObject {
                        put("rule_id", ruleId)
                        put("versions", JsonArray(history.map { it.toJsonObject() }))
                    }.toString(),
                )
            } catch (error: IllegalArgumentException) {
                call.respondApiError(error)
            }
        }

        get("/decisions/{event_id}") {
            val eventId =
                try {
                    parseDecisionLookupEventId(call.parameters["event_id"].orEmpty())
                } catch (error: IllegalArgumentException) {
                    call.respondJson(errorJson(error.message.orEmpty()), HttpStatusCode.BadRequest)
                    return@get
                }
            val record = decisionRecords.find(EventId(eventId))
            if (record == null) {
                call.respondJson(errorJson("decision not found: $eventId"), HttpStatusCode.NotFound)
            } else {
                call.respondJson(record.toApiJsonObject().toString())
            }
        }
    }
}

internal fun parseDecisionLookupEventId(rawEventId: String): String {
    val eventId = rawEventId.trim()
    require(eventId.isNotBlank()) { "event_id is required" }
    return eventId
}

fun startAdminHttpServer(
    ruleAdminService: RuleAdminService,
    decisionRecords: DecisionRecordReader,
    host: String = "127.0.0.1",
    port: Int = 8080,
    metricsPath: String = "/metrics",
    metricsScrape: (() -> String)? = null,
) = embeddedServer(Netty, host = host, port = port) {
    installControlsAdminRoutes(
        ruleAdminService = ruleAdminService,
        decisionRecords = decisionRecords,
        metricsPath = metricsPath,
        metricsScrape = metricsScrape,
    )
}.start(wait = false)

private suspend fun io.ktor.server.application.ApplicationCall.respondJson(
    payload: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondText(payload, ContentType.Application.Json, status)
}

private suspend fun io.ktor.server.application.ApplicationCall.respondApiError(error: IllegalArgumentException) {
    val status =
        when (error) {
            is RuleAdminException.BadRequest,
            is ApiJsonException,
            -> HttpStatusCode.BadRequest
            is RuleAdminException.Conflict -> HttpStatusCode.Conflict
            is RuleAdminException.NotFound -> HttpStatusCode.NotFound
            else -> HttpStatusCode.BadRequest
        }
    respondJson(errorJson(error.message.orEmpty()), status)
}
