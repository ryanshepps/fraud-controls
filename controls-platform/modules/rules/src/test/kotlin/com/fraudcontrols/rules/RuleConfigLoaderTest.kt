package com.fraudcontrols.rules

import com.fraudcontrols.core.DecisionAction
import com.fraudcontrols.core.EventId
import com.fraudcontrols.core.FeatureSnapshot
import com.fraudcontrols.core.FeatureValue
import com.fraudcontrols.core.ReasonCode
import com.fraudcontrols.features.FraudFeatureNames
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RuleConfigLoaderTest {
    private val loader = RuleConfigLoader()

    @Test
    fun `loads prompt-shaped yaml rules`() {
        val ruleSet = loader.load(StringReader(promptShapeYaml()))

        assertEquals(1, ruleSet.version)
        assertEquals(listOf("new_user_high_score_block", "mule_fan_in_review"), ruleSet.rules.map { it.id })

        val blockRule = ruleSet.rules.first()
        assertEquals(1, blockRule.version)
        assertEquals("Block high-risk transactions from accounts under 7 days", blockRule.description)
        assertEquals(RuleMode.ENFORCE, blockRule.mode)
        assertEquals(100, blockRule.priority)
        assertEquals(RuleActionType.BLOCK, blockRule.action.type)
        assertEquals(ReasonCode("NEW_USER_HIGH_RISK"), blockRule.action.reasonCode)
        assertEquals(true, blockRule.action.reversible)

        val reviewRule = ruleSet.rules.last()
        assertEquals(RuleMode.SHADOW, reviewRule.mode)
        assertEquals(RuleActionType.REVIEW_QUEUE, reviewRule.action.type)
        assertEquals("trust_safety_l2", reviewRule.action.queue)
        assertEquals(false, reviewRule.action.reversible)
    }

    @Test
    fun `validates rule feature names before activation`() {
        val ruleSet = loader.load(
            StringReader(
                """
                version: 1
                rules:
                  - id: unknown_feature_rule
                    mode: enforce
                    when:
                      feature: not_registered
                      op: gt
                      value: 1
                    action:
                      type: block
                      reason_code: UNKNOWN
                """.trimIndent(),
            ),
        )

        val error = assertFailsWith<RuleValidationException> {
            RuleValidator(setOf(FraudFeatureNames.AMOUNT)).validate(ruleSet)
        }

        assertContains(error.message.orEmpty(), "unknown_feature_rule: unknown feature: not_registered")
    }

    @Test
    fun `rejects malformed yaml contract fields`() {
        val cases = listOf(
            "version: 1\nrules: []" to null,
            "rules: []" to "rules.version is required",
            "version: 1\nrules:\n  - id: bad" to "when must be an object",
            """
            version: 1
            rules:
              - id: bad_action
                when:
                  feature: amount
                  op: gt
                  value: 1
                action:
                  type: email
            """.trimIndent() to "unsupported rule action type: email",
        )

        for ((yaml, expectedError) in cases) {
            if (expectedError == null) {
                assertEquals(emptyList(), loader.load(StringReader(yaml)).rules)
            } else {
                val error = assertFailsWith<RuleConfigException> {
                    loader.load(StringReader(yaml))
                }
                assertContains(error.message.orEmpty(), expectedError)
            }
        }
    }
}

class RuleEvaluatorTest {
    private val evaluator = RuleEvaluator()

    @Test
    fun `evaluates all any not and comparison operators`() {
        val operatorCases = listOf(
            "eq" to RuleCondition.Comparison("amount", ComparisonOperator.EQ, RuleValue.NumberValue(1000.0)),
            "neq" to RuleCondition.Comparison("transaction_type", ComparisonOperator.NEQ, RuleValue.TextValue("CASH_OUT")),
            "lt" to RuleCondition.Comparison("sender_account_age_days", ComparisonOperator.LT, RuleValue.NumberValue(7.0)),
            "lte" to RuleCondition.Comparison("amount", ComparisonOperator.LTE, RuleValue.NumberValue(1000.0)),
            "gt" to RuleCondition.Comparison("fraud_model_score", ComparisonOperator.GT, RuleValue.NumberValue(0.85)),
            "gte" to RuleCondition.Comparison("amount", ComparisonOperator.GTE, RuleValue.NumberValue(1000.0)),
            "in" to RuleCondition.Comparison(
                "transaction_type",
                ComparisonOperator.IN,
                RuleValue.SetValue(setOf(RuleValue.TextValue("P2P_SEND"), RuleValue.TextValue("CASH_OUT"))),
            ),
            "not_in" to RuleCondition.Comparison(
                "transaction_type",
                ComparisonOperator.NOT_IN,
                RuleValue.SetValue(setOf(RuleValue.TextValue("CARD_PAYMENT"))),
            ),
            "all_any_not" to RuleCondition.All(
                listOf(
                    RuleCondition.Any(
                        listOf(
                            RuleCondition.Comparison("amount", ComparisonOperator.GT, RuleValue.NumberValue(900.0)),
                            RuleCondition.Comparison("amount", ComparisonOperator.LT, RuleValue.NumberValue(10.0)),
                        ),
                    ),
                    RuleCondition.Not(
                        RuleCondition.Comparison("is_new_counterparty", ComparisonOperator.EQ, RuleValue.BooleanValue(false)),
                    ),
                ),
            ),
        )

        val rules = operatorCases.map { (id, condition) ->
            rule(id = id, condition = condition)
        }

        val result = evaluator.evaluate(sampleSnapshot(), rules)

        assertEquals(operatorCases.map { it.first }, result.matches.map { it.ruleId })
        assertEquals(emptyList(), result.skipped)
    }

    @Test
    fun `skips disabled and unavailable rules without matching them`() {
        val result = evaluator.evaluate(
            snapshot = sampleSnapshot(
                values = mapOf(
                    "amount" to FeatureValue.TextValue("1000.00"),
                    "fraud_model_score" to FeatureValue.Unavailable("redis timeout"),
                    "is_new_counterparty" to FeatureValue.BooleanValue(true),
                ),
            ),
            rules = listOf(
                rule(
                    id = "disabled-rule",
                    enabled = false,
                    condition = RuleCondition.Comparison(
                        "is_new_counterparty",
                        ComparisonOperator.EQ,
                        RuleValue.BooleanValue(true),
                    ),
                ),
                rule(
                    id = "wrong-type-rule",
                    condition = RuleCondition.Comparison("amount", ComparisonOperator.GT, RuleValue.NumberValue(500.0)),
                ),
                rule(
                    id = "unavailable-rule",
                    condition = RuleCondition.Comparison(
                        "fraud_model_score",
                        ComparisonOperator.GT,
                        RuleValue.NumberValue(0.85),
                    ),
                ),
            ),
        )

        assertEquals(emptyList(), result.matches)
        assertEquals(
            listOf(
                SkippedRule("disabled-rule", 1, "rule is disabled"),
                SkippedRule("wrong-type-rule", 1, "feature amount expected numeric for comparison but was text"),
                SkippedRule("unavailable-rule", 1, "feature fraud_model_score unavailable: redis timeout"),
            ),
            result.skipped,
        )
        assertEquals(
            listOf(
                RuleEvaluationConditionResult.DISABLED,
                RuleEvaluationConditionResult.UNAVAILABLE,
                RuleEvaluationConditionResult.UNAVAILABLE,
            ),
            result.evaluations.map { it.conditionResult },
        )
        assertEquals(FeatureValue.TextValue("1000.00"), result.evaluations[1].featureValues["amount"])
        assertEquals(FeatureValue.Unavailable("redis timeout"), result.evaluations[2].featureValues["fraud_model_score"])
    }

    @Test
    fun `records complete per rule evaluation details`() {
        val result = evaluator.evaluate(
            snapshot = sampleSnapshot(
                values = linkedMapOf(
                    "amount" to FeatureValue.NumberValue(1000.0),
                ),
            ),
            rules = listOf(
                rule(
                    id = "matched-rule",
                    priority = 100,
                    action = RuleAction(
                        type = RuleActionType.REVIEW_QUEUE,
                        reasonCode = ReasonCode("REVIEW"),
                        reversible = true,
                        queue = "trust_safety_l2",
                    ),
                    condition = RuleCondition.Comparison("amount", ComparisonOperator.GTE, RuleValue.NumberValue(1000.0)),
                ),
                rule(
                    id = "non-matching-rule",
                    priority = 200,
                    action = RuleAction(type = RuleActionType.ALLOW),
                    condition = RuleCondition.Comparison("amount", ComparisonOperator.LT, RuleValue.NumberValue(1000.0)),
                ),
            ),
        )

        assertEquals(listOf("matched-rule"), result.matches.map { it.ruleId })
        assertEquals(
            listOf(RuleEvaluationConditionResult.MATCHED, RuleEvaluationConditionResult.NOT_MATCHED),
            result.evaluations.map { it.conditionResult },
        )
        assertEquals(listOf(setOf("amount"), setOf("amount")), result.evaluations.map { it.featureValues.keys })
        assertEquals("matched-rule", result.resolutionCandidates.single().ruleId)
    }

    @Test
    fun `shadow matches do not resolve final actions`() {
        val result = evaluator.evaluate(
            snapshot = sampleSnapshot(),
            rules = listOf(
                rule(
                    id = "shadow-block",
                    mode = RuleMode.SHADOW,
                    priority = 100,
                    action = RuleAction(
                        type = RuleActionType.BLOCK,
                        reasonCode = ReasonCode("SHADOW_BLOCK"),
                        reversible = true,
                    ),
                    condition = RuleCondition.Comparison("amount", ComparisonOperator.GT, RuleValue.NumberValue(500.0)),
                ),
            ),
        )

        assertEquals(listOf("shadow-block"), result.matches.map { it.ruleId })
        assertNull(result.resolvedAction)
    }

    @Test
    fun `resolves enforce action conflicts by priority then severity then rule id`() {
        val result = evaluator.evaluate(
            snapshot = sampleSnapshot(),
            rules = listOf(
                rule(
                    id = "challenge-high-priority",
                    priority = 100,
                    action = RuleAction(
                        type = RuleActionType.CHALLENGE,
                        reasonCode = ReasonCode("STEP_UP"),
                        reversible = true,
                    ),
                ),
                rule(
                    id = "block-lower-priority",
                    priority = 50,
                    action = RuleAction(
                        type = RuleActionType.BLOCK,
                        reasonCode = ReasonCode("BLOCK"),
                        reversible = true,
                    ),
                ),
                rule(
                    id = "tag-highest-priority",
                    priority = 200,
                    action = RuleAction(type = RuleActionType.TAG, tag = "watchlist"),
                ),
            ),
        )

        assertEquals("challenge-high-priority", result.resolvedAction?.ruleId)
        assertEquals(DecisionAction.CHALLENGE, result.resolvedAction?.decisionAction)
        assertEquals(ReasonCode("STEP_UP"), result.resolvedAction?.action?.reasonCode)
    }

    @Test
    fun `requires immutable versions and action metadata`() {
        assertFailsWith<IllegalArgumentException> {
            rule(version = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            RuleAction(type = RuleActionType.REVIEW_QUEUE)
        }
        assertFailsWith<IllegalArgumentException> {
            RuleAction(type = RuleActionType.TAG)
        }
    }

    private fun sampleSnapshot(
        values: Map<String, FeatureValue> = mapOf(
            "amount" to FeatureValue.NumberValue(1000.0),
            "fraud_model_score" to FeatureValue.NumberValue(0.91),
            "sender_account_age_days" to FeatureValue.NumberValue(2.0),
            "is_new_counterparty" to FeatureValue.BooleanValue(true),
            "transaction_type" to FeatureValue.TextValue("P2P_SEND"),
        ),
    ): FeatureSnapshot = FeatureSnapshot(
        eventId = EventId("evt-1"),
        values = values,
    )

    private fun rule(
        id: String = "rule-1",
        version: Int = 1,
        enabled: Boolean = true,
        mode: RuleMode = RuleMode.ENFORCE,
        priority: Int = 0,
        action: RuleAction = RuleAction(
            type = RuleActionType.BLOCK,
            reasonCode = ReasonCode("RULE_HIT"),
            reversible = true,
        ),
        condition: RuleCondition = RuleCondition.Comparison(
            "is_new_counterparty",
            ComparisonOperator.EQ,
            RuleValue.BooleanValue(true),
        ),
    ): RuleDefinition = RuleDefinition(
        id = id,
        version = version,
        enabled = enabled,
        mode = mode,
        priority = priority,
        condition = condition,
        action = action,
    )
}

private fun promptShapeYaml(): String =
    """
    version: 1
    rules:
      - id: new_user_high_score_block
        description: Block high-risk transactions from accounts under 7 days
        enabled: true
        mode: enforce
        priority: 100
        when:
          all:
            - feature: sender_account_age_days
              op: lt
              value: 7
            - feature: fraud_model_score
              op: gt
              value: 0.85
            - feature: amount
              op: gt
              value: 500
        action:
          type: block
          reason_code: NEW_USER_HIGH_RISK
          reversible: true

      - id: mule_fan_in_review
        mode: shadow
        when:
          any:
            - all:
                - feature: recipient_in_degree_7d
                  op: gt
                  value: 10
                - feature: recipient_account_age_days
                  op: lt
                  value: 30
        action:
          type: review_queue
          queue: trust_safety_l2
    """.trimIndent()
