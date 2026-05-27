package com.fraudcontrols.observability

import com.fraudcontrols.core.DecisionAction
import com.fraudcontrols.decisioning.DecisionSideEffect
import com.fraudcontrols.rules.RuleActionType
import com.fraudcontrols.rules.RuleMode
import io.prometheus.metrics.tracer.common.SpanContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MicrometerControlsMetricsTest {
    @Test
    fun `exports decision latency rule and shadow metrics in prometheus format`() {
        val metrics = MicrometerControlsMetrics()

        metrics.recordDecision(DecisionAction.DENY)
        metrics.recordDecisionLatency(42.0)
        metrics.recordDecisionSideEffectFailure(DecisionSideEffect.AUDIT_RECORD)
        metrics.recordFeatureResolutionLatency(3.0)
        metrics.recordRuleEvaluationLatency(4.0)
        metrics.recordScoringLatency("primary", "v1", degraded = false, latencyMs = 5.0)
        metrics.recordRuleMatch("high-score", RuleMode.SHADOW, RuleActionType.BLOCK)
        metrics.updateShadowRuleMetrics(
            ruleId = "high-score",
            fireRate = 0.25,
            wouldHaveBlockedRate = 0.5,
            agreementRate = 0.75,
        )
        metrics.updateScorerPairMetrics(
            primaryScorer = "primary",
            shadowScorer = "candidate",
            scoreDivergence = 0.12,
            decisionFlipRate = 0.2,
        )

        val scrape = metrics.scrape()

        assertTrue(scrape.contains("controls_decisions_total{action=\"DENY\"} 1.0"))
        assertTrue(scrape.contains("controls_decision_latency_seconds_bucket"))
        assertTrue(scrape.contains("controls_decision_side_effect_failures_total{side_effect=\"AUDIT_RECORD\"} 1.0"))
        assertTrue(scrape.contains("controls_feature_resolution_latency_seconds_bucket"))
        assertTrue(scrape.contains("controls_rule_evaluation_latency_seconds_bucket"))
        assertTrue(scrape.contains("controls_scoring_latency_seconds_bucket"))
        assertTrue(scrape.contains("controls_rule_fire_total{action_type=\"BLOCK\",mode=\"SHADOW\",rule_id=\"high-score\"} 1.0"))
        assertTrue(scrape.contains("controls_shadow_rule_would_have_blocked_rate{rule_id=\"high-score\"} 0.5"))
        assertTrue(scrape.contains("controls_scorer_score_divergence"))
        assertTrue(scrape.contains("controls_scorer_decision_flip_rate"))
    }

    @Test
    fun `exports openmetrics exemplars with current trace context`() {
        val spanContext = RecordingSpanContext()
        val metrics = MicrometerControlsMetrics.withSpanContext(spanContext)

        metrics.recordDecisionLatency(42.0)
        val scrape = metrics.scrape("application/openmetrics-text; version=1.0.0")

        assertTrue(scrape.contains("# EOF"))
        assertTrue(scrape.contains("trace_id=\"0123456789abcdef0123456789abcdef\""))
        assertTrue(scrape.contains("span_id=\"0123456789abcdef\""))
        assertEquals(1, spanContext.markedCount)
    }
}

private class RecordingSpanContext : SpanContext {
    var markedCount = 0

    override fun getCurrentTraceId(): String = "0123456789abcdef0123456789abcdef"

    override fun getCurrentSpanId(): String = "0123456789abcdef"

    override fun isCurrentSpanSampled(): Boolean = true

    override fun markCurrentSpanAsExemplar() {
        markedCount += 1
    }
}
