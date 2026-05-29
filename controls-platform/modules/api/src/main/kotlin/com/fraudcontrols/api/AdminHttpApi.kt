package com.fraudcontrols.api

import com.fraudcontrols.core.EventId
import com.fraudcontrols.decisioning.DecisionRecordReader
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.JsonConvertException
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.decodeFromString

fun Application.installControlsAdminRoutes(
    ruleAdminService: RuleAdminService,
    decisionRecords: DecisionRecordReader,
    metricsPath: String = "/metrics",
    metricsScrape: (() -> String)? = null,
) {
    installControlsAdminApiPlugins()

    routing {
        if (metricsScrape != null) {
            get(metricsPath) {
                call.respondText(metricsScrape(), ContentType.Text.Plain)
            }
        }

        get("/rules") {
            call.respond(RuleListResponse(ruleAdminService.list().map { it.toResponse() }))
        }

        post("/rules") {
            val request = call.receive<RuleDefinitionRequest>()
            val created =
                ruleAdminService.create(
                    rule = request.toRuleDefinition(),
                    actor = request.actorOrDefault(),
                )
            call.respond(HttpStatusCode.Created, created.toResponse())
        }

        put("/rules/{id}") {
            val ruleId = parseRequiredPathParameter("id", call.parameters["id"].orEmpty())
            val request = call.receive<RuleDefinitionRequest>()
            val updated =
                ruleAdminService.update(
                    ruleId = ruleId,
                    replacement = request.toRuleDefinition(idOverride = ruleId),
                    actor = request.actorOrDefault(),
                )
            call.respond(updated.toResponse())
        }

        post("/rules/{id}/promote") {
            val ruleId = parseRequiredPathParameter("id", call.parameters["id"].orEmpty())
            val request = call.receiveOptionalJsonBody(PromotionRequest())
            val promoted =
                ruleAdminService.promote(
                    ruleId = ruleId,
                    confirmed = request.confirm,
                    actor = request.actorOrDefault(),
                )
            call.respond(promoted.toResponse())
        }

        post("/rules/{id}/disable") {
            val ruleId = parseRequiredPathParameter("id", call.parameters["id"].orEmpty())
            val request = call.receiveOptionalJsonBody(RuleActorRequest())
            val disabled =
                ruleAdminService.disable(
                    ruleId = ruleId,
                    actor = request.actorOrDefault(),
                )
            call.respond(disabled.toResponse())
        }

        get("/rules/{id}/history") {
            val ruleId = parseRequiredPathParameter("id", call.parameters["id"].orEmpty())
            val history = ruleAdminService.history(ruleId)
            call.respond(RuleHistoryResponse(ruleId = ruleId, versions = history.map { it.toResponse() }))
        }

        get("/decisions/{event_id}") {
            val eventId = parseDecisionLookupEventId(call.parameters["event_id"].orEmpty())
            val record = decisionRecords.find(EventId(eventId))
            if (record == null) {
                call.respond(HttpStatusCode.NotFound, ApiErrorResponse("decision not found: $eventId"))
            } else {
                call.respond(record.toApiResponse())
            }
        }
    }
}

internal fun parseDecisionLookupEventId(rawEventId: String): String = parseRequiredPathParameter("event_id", rawEventId)

private suspend inline fun <reified T> io.ktor.server.application.ApplicationCall.receiveOptionalJsonBody(defaultValue: T): T {
    val payload = receiveText()
    if (payload.isBlank()) {
        return defaultValue
    }
    return try {
        apiJson.decodeFromString(payload)
    } catch (error: IllegalArgumentException) {
        throw ApiJsonException(error.message ?: "invalid JSON body")
    }
}

private fun parseRequiredPathParameter(
    name: String,
    rawValue: String,
): String {
    val value = rawValue.trim()
    if (value.isBlank()) {
        throw ApiJsonException("$name is required")
    }
    return value
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

private fun Application.installControlsAdminApiPlugins() {
    install(ContentNegotiation) {
        json(apiJson)
    }

    install(StatusPages) {
        exception<ApiJsonException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(cause.message.orEmpty()))
        }
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(cause.message ?: "invalid request body"))
        }
        exception<JsonConvertException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(cause.message ?: "invalid JSON body"))
        }
        exception<RuleAdminException.BadRequest> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(cause.message.orEmpty()))
        }
        exception<RuleAdminException.Conflict> { call, cause ->
            call.respond(HttpStatusCode.Conflict, ApiErrorResponse(cause.message.orEmpty()))
        }
        exception<RuleAdminException.NotFound> { call, cause ->
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse(cause.message.orEmpty()))
        }
    }
}
