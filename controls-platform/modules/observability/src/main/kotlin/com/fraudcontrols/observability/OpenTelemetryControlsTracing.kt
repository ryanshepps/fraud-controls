package com.fraudcontrols.observability

import com.fraudcontrols.decisioning.DecisioningTracer
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

data class ControlsTelemetry(
    val openTelemetry: OpenTelemetry,
    val decisioningTracer: DecisioningTracer,
    val prometheusSpanContext: OpenTelemetryPrometheusSpanContext,
)

object ControlsTelemetryFactory {
    fun fromEnvironment(env: (String) -> String? = System::getenv): ControlsTelemetry {
        val openTelemetry = if (env.isTracingEnabled()) {
            AutoConfiguredOpenTelemetrySdk.initialize().openTelemetrySdk
        } else {
            OpenTelemetry.noop()
        }
        return ControlsTelemetry(
            openTelemetry = openTelemetry,
            decisioningTracer = OpenTelemetryDecisioningTracer(
                openTelemetry.getTracer("com.fraudcontrols.controls-platform"),
            ),
            prometheusSpanContext = OpenTelemetryPrometheusSpanContext(),
        )
    }

    private fun ((String) -> String?).isTracingEnabled(): Boolean =
        this("OTEL_ENABLED")?.toBooleanStrictOrNull()
            ?: (this("OTEL_EXPORTER_OTLP_ENDPOINT") != null || this("OTEL_TRACES_EXPORTER") != null)
}

class OpenTelemetryDecisioningTracer(
    private val tracer: Tracer,
) : DecisioningTracer {
    override suspend fun <T> span(
        name: String,
        attributes: Map<String, String>,
        block: suspend () -> T,
    ): T {
        val span = tracer.spanBuilder(name).startSpan()
        attributes.forEach { (key, value) ->
            span.setAttribute(AttributeKey.stringKey(key), value)
        }
        val context = Context.current().with(span)
        return try {
            withContext(context.asContextElement()) {
                block()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            span.recordException(error)
            span.setStatus(StatusCode.ERROR, error.message.orEmpty())
            throw error
        } finally {
            span.end()
        }
    }
}

class OpenTelemetryPrometheusSpanContext : io.prometheus.metrics.tracer.common.SpanContext {
    override fun getCurrentTraceId(): String =
        Span.current().spanContext.takeIf { it.isValid }?.traceId.orEmpty()

    override fun getCurrentSpanId(): String =
        Span.current().spanContext.takeIf { it.isValid }?.spanId.orEmpty()

    override fun isCurrentSpanSampled(): Boolean =
        Span.current().spanContext.takeIf { it.isValid }?.traceFlags?.isSampled ?: false

    override fun markCurrentSpanAsExemplar() {
        val span = Span.current()
        if (span.spanContext.isValid) {
            span.setAttribute(
                io.prometheus.metrics.tracer.common.SpanContext.EXEMPLAR_ATTRIBUTE_NAME,
                io.prometheus.metrics.tracer.common.SpanContext.EXEMPLAR_ATTRIBUTE_VALUE,
            )
        }
    }
}
