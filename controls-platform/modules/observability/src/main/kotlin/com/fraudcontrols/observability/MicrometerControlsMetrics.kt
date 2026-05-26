package com.fraudcontrols.observability

import com.fraudcontrols.core.DecisionAction
import com.fraudcontrols.decisioning.DecisionSideEffect
import com.fraudcontrols.rules.RuleActionType
import com.fraudcontrols.rules.RuleMode
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Timer
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class MicrometerControlsMetrics(
    val registry: PrometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT),
) : ControlsMetrics {
    fun scrape(): String = registry.scrape()

    override fun recordDecision(action: DecisionAction) {
        registry.counter("controls.decisions", "action", action.name).increment()
    }

    override fun recordDecisionLatency(latencyMs: Double) {
        histogramTimer("controls.decision.latency").record(latencyMs.toDuration())
    }

    override fun recordDecisionSideEffectFailure(sideEffect: DecisionSideEffect) {
        registry.counter("controls.decision.side_effect.failures", "side_effect", sideEffect.name).increment()
    }

    override fun recordFeatureResolutionLatency(latencyMs: Double) {
        histogramTimer("controls.feature.resolution.latency").record(latencyMs.toDuration())
    }

    override fun recordScoringLatency(
        scorerName: String,
        scorerVersion: String,
        degraded: Boolean,
        latencyMs: Double,
    ) {
        histogramTimer(
            name = "controls.scoring.latency",
            tags = listOf(
                Tag.of("scorer", scorerName),
                Tag.of("version", scorerVersion),
                Tag.of("degraded", degraded.toString()),
            ),
        ).record(latencyMs.toDuration())
    }

    override fun recordRuleEvaluationLatency(latencyMs: Double) {
        histogramTimer("controls.rule.evaluation.latency").record(latencyMs.toDuration())
    }

    override fun recordRuleMatch(
        ruleId: String,
        mode: RuleMode,
        actionType: RuleActionType,
    ) {
        registry.counter(
            "controls.rule.fire",
            "rule_id",
            ruleId,
            "mode",
            mode.name,
            "action_type",
            actionType.name,
        ).increment()
    }

    override fun updateShadowRuleMetrics(
        ruleId: String,
        fireRate: Double,
        wouldHaveBlockedRate: Double,
        agreementRate: Double,
    ) {
        gauge("controls.shadow.rule.fire.rate", listOf(Tag.of("rule_id", ruleId))).set(fireRate)
        gauge("controls.shadow.rule.would.have.blocked.rate", listOf(Tag.of("rule_id", ruleId)))
            .set(wouldHaveBlockedRate)
        gauge("controls.shadow.rule.agreement.rate", listOf(Tag.of("rule_id", ruleId))).set(agreementRate)
    }

    override fun updateScorerPairMetrics(
        primaryScorer: String,
        shadowScorer: String,
        scoreDivergence: Double,
        decisionFlipRate: Double,
    ) {
        val tags = listOf(
            Tag.of("primary_scorer", primaryScorer),
            Tag.of("shadow_scorer", shadowScorer),
        )
        gauge("controls.scorer.score.divergence", tags).set(scoreDivergence)
        gauge("controls.scorer.decision.flip.rate", tags).set(decisionFlipRate)
    }

    private fun histogramTimer(
        name: String,
        tags: List<Tag> = emptyList(),
    ): Timer =
        Timer.builder(name)
            .publishPercentileHistogram()
            .minimumExpectedValue(Duration.ofMillis(1))
            .maximumExpectedValue(Duration.ofSeconds(10))
            .tags(tags)
            .register(registry)

    private fun gauge(
        name: String,
        tags: List<Tag>,
    ): AtomicReference<Double> {
        val key = "$name:${tags.joinToString(",") { "${it.key}=${it.value}" }}"
        return gauges.computeIfAbsent(key) {
            AtomicReference(0.0).also { value ->
                Gauge.builder(name, value) { it.get() }
                    .tags(tags)
                    .register(registry)
            }
        }
    }

    private val gauges = ConcurrentHashMap<String, AtomicReference<Double>>()
}

fun MeterRegistry.controlsMetricsScrapeOrNull(): String? =
    (this as? PrometheusMeterRegistry)?.scrape()

private fun Double.toDuration(): Duration =
    Duration.ofNanos((this * 1_000_000.0).toLong().coerceAtLeast(0L))
