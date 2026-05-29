package com.fraudcontrols.observability

import com.fraudcontrols.core.DecisionAction
import com.fraudcontrols.core.EventId
import com.fraudcontrols.core.Factor
import com.fraudcontrols.core.FeatureValue
import com.fraudcontrols.core.ScoreResult
import com.fraudcontrols.rules.ResolvedRuleAction
import com.fraudcontrols.rules.RuleAction
import com.fraudcontrols.rules.RuleActionType
import com.fraudcontrols.rules.RuleEvaluationConditionResult
import com.fraudcontrols.rules.RuleEvaluationDetail
import com.fraudcontrols.rules.RuleEvaluationResult
import com.fraudcontrols.rules.RuleMode
import com.fraudcontrols.scoring.ShadowEvaluation
import com.fraudcontrols.scoring.ShadowScorerRole
import kotlin.test.Test
import kotlin.test.assertTrue

class ShadowEvaluationReporterTest {
    @Test
    fun `computes scorer divergence decision flips and shadow rule outcomes`() {
        val metrics = MicrometerControlsMetrics()
        val reporter = ShadowEvaluationReporter(metrics, threshold = 0.5, windowSize = 10)

        reporter.recordShadowEvaluations(
            listOf(
                shadowEvaluation("primary", ShadowScorerRole.PRIMARY, 0.80),
                shadowEvaluation("candidate", ShadowScorerRole.SHADOW, 0.40),
            ),
        )
        reporter.recordRuleEvaluation(
            RuleEvaluationResult(
                eventId = EventId("evt-1"),
                evaluations = listOf(
                    RuleEvaluationDetail(
                        ruleId = "shadow-high-score",
                        ruleVersion = 1,
                        mode = RuleMode.SHADOW,
                        priority = 100,
                        conditionResult = RuleEvaluationConditionResult.MATCHED,
                        action = RuleAction(type = RuleActionType.BLOCK),
                        featureValues = mapOf("fraud_model_score" to FeatureValue.NumberValue(0.9)),
                    ),
                ),
                resolvedAction = ResolvedRuleAction(
                    ruleId = "enforce-high-score",
                    ruleVersion = 1,
                    decisionAction = DecisionAction.DENY,
                    action = RuleAction(type = RuleActionType.BLOCK),
                    priority = 100,
                ),
            ),
        )
        reporter.recordRuleEvaluation(
            RuleEvaluationResult(
                eventId = EventId("evt-2"),
                evaluations = emptyList(),
                resolvedAction = null,
            ),
        )

        val scrape = metrics.scrape()

        assertTrue(scrape.contains("controls_scorer_score_divergence{primary_scorer=\"primary\",shadow_scorer=\"candidate\"} 0.4"))
        assertTrue(scrape.contains("controls_scorer_decision_flip_rate{primary_scorer=\"primary\",shadow_scorer=\"candidate\"} 1.0"))
        assertTrue(scrape.contains("controls_shadow_rule_fire_rate{rule_id=\"shadow-high-score\"} 0.5"))
        assertTrue(scrape.contains("controls_shadow_rule_would_have_blocked_rate{rule_id=\"shadow-high-score\"} 0.5"))
        assertTrue(scrape.contains("controls_shadow_rule_agreement_rate{rule_id=\"shadow-high-score\"} 1.0"))
    }

    @Test
    fun `reads shadow evaluation and rule evaluation event payloads`() {
        val metrics = MicrometerControlsMetrics()
        val reporter = ShadowEvaluationReporter(metrics, threshold = 0.5)
        val shadowPayload = listOf(
            shadowEvaluation("primary", ShadowScorerRole.PRIMARY, 0.10),
            shadowEvaluation("candidate", ShadowScorerRole.SHADOW, 0.90),
        ).toShadowEvaluationEventJsonString()
        val rulePayload =
            """
            {
              "schema_version": 1,
              "event_id": "evt-1",
              "matches": [
                {
                  "rule_id": "shadow-score",
                  "rule_version": 1,
                  "mode": "SHADOW",
                  "priority": 100,
                  "action_type": "CHALLENGE"
                }
              ],
              "skipped": [],
              "resolved_action": {
                "rule_id": "enforce-score",
                "rule_version": 1,
                "decision_action": "CHALLENGE",
                "priority": 100,
                "action_type": "CHALLENGE"
              }
            }
            """.trimIndent()

        reporter.recordShadowEvaluationPayload(shadowPayload)
        reporter.recordRuleEvaluationPayload(rulePayload)

        val scrape = metrics.scrape()

        assertTrue(scrape.contains("controls_scorer_decision_flip_rate{primary_scorer=\"primary\",shadow_scorer=\"candidate\"} 1.0"))
        assertTrue(scrape.contains("controls_shadow_rule_would_have_blocked_rate{rule_id=\"shadow-score\"} 1.0"))
        assertTrue(scrape.contains("controls_shadow_rule_agreement_rate{rule_id=\"shadow-score\"} 1.0"))
    }

    private fun shadowEvaluation(
        scorerName: String,
        role: ShadowScorerRole,
        score: Double,
    ): ShadowEvaluation = ShadowEvaluation(
        eventId = EventId("evt-1"),
        scorerName = scorerName,
        scorerVersion = "v1",
        role = role,
        result = ScoreResult(
            score = score,
            rawScore = null,
            contributingFactors = listOf(Factor("fixed", score)),
            modelVersion = "v1",
            latencyMs = 1.0,
        ),
        error = null,
    )
}
