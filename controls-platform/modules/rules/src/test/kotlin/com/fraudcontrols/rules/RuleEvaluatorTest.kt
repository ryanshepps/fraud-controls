package com.fraudcontrols.rules

import com.fraudcontrols.core.DecisionAction
import com.fraudcontrols.core.EventId
import com.fraudcontrols.core.FeatureSnapshot
import com.fraudcontrols.core.FeatureValue
import com.fraudcontrols.core.ReasonCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RuleEvaluatorTest {
    private val evaluator = RuleEvaluator()

    @Test
    fun `matches rules with numeric boolean text and all conditions`() {
        val result = evaluator.evaluate(
            snapshot = sampleSnapshot(),
            rules = listOf(
                rule(
                    id = "large-new-counterparty",
                    action = DecisionAction.HOLD,
                    reasonCode = "large_new_counterparty",
                    condition = RuleCondition.All(
                        listOf(
                            RuleCondition.NumberCompare(
                                featureName = "amount",
                                operator = NumericOperator.GREATER_THAN_OR_EQUAL,
                                threshold = 1000.0,
                            ),
                            RuleCondition.BooleanEquals("is_new_counterparty", true),
                            RuleCondition.TextEquals("transaction_type", "P2P_SEND"),
                        ),
                    ),
                ),
                rule(
                    id = "small-transfer",
                    action = DecisionAction.ALLOW,
                    reasonCode = "small_transfer",
                    condition = RuleCondition.NumberCompare(
                        featureName = "amount",
                        operator = NumericOperator.LESS_THAN,
                        threshold = 10.0,
                    ),
                ),
            ),
        )

        assertEquals(EventId("evt-1"), result.eventId)
        assertEquals(
            listOf(
                RuleMatch(
                    ruleId = "large-new-counterparty",
                    action = DecisionAction.HOLD,
                    reasonCode = ReasonCode("large_new_counterparty"),
                ),
            ),
            result.matches,
        )
        assertEquals(emptyList(), result.skipped)
    }

    @Test
    fun `skips rules when required features are missing or wrong type`() {
        val result = evaluator.evaluate(
            snapshot = sampleSnapshot(
                values = mapOf(
                    "amount" to FeatureValue.TextValue("1000.00"),
                    "is_new_counterparty" to FeatureValue.BooleanValue(true),
                ),
            ),
            rules = listOf(
                rule(
                    id = "amount-rule",
                    condition = RuleCondition.NumberCompare(
                        featureName = "amount",
                        operator = NumericOperator.GREATER_THAN,
                        threshold = 500.0,
                    ),
                ),
                rule(
                    id = "type-rule",
                    condition = RuleCondition.TextEquals("transaction_type", "P2P_SEND"),
                ),
            ),
        )

        assertEquals(emptyList(), result.matches)
        assertEquals(
            listOf(
                SkippedRule("amount-rule", "feature amount expected numeric but was text"),
                SkippedRule("type-rule", "missing text feature: transaction_type"),
            ),
            result.skipped,
        )
    }

    @Test
    fun `skips all condition when no child condition rejects and one child is unavailable`() {
        val result = evaluator.evaluate(
            snapshot = sampleSnapshot(
                values = mapOf(
                    "amount" to FeatureValue.NumberValue(1250.0),
                ),
            ),
            rules = listOf(
                rule(
                    id = "compound-rule",
                    condition = RuleCondition.All(
                        listOf(
                            RuleCondition.NumberCompare(
                                featureName = "amount",
                                operator = NumericOperator.GREATER_THAN,
                                threshold = 1000.0,
                            ),
                            RuleCondition.BooleanEquals("is_new_counterparty", true),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(emptyList(), result.matches)
        assertEquals(
            listOf(SkippedRule("compound-rule", "missing boolean feature: is_new_counterparty")),
            result.skipped,
        )
    }

    @Test
    fun `validates rule definitions and conditions`() {
        assertFailsWith<IllegalArgumentException> {
            rule(id = "")
        }
        assertFailsWith<IllegalArgumentException> {
            RuleCondition.NumberCompare("amount", NumericOperator.GREATER_THAN, Double.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            RuleCondition.All(emptyList())
        }
    }

    private fun sampleSnapshot(
        values: Map<String, FeatureValue> = mapOf(
            "amount" to FeatureValue.NumberValue(1250.0),
            "is_new_counterparty" to FeatureValue.BooleanValue(true),
            "transaction_type" to FeatureValue.TextValue("P2P_SEND"),
        ),
    ): FeatureSnapshot =
        FeatureSnapshot(
            eventId = EventId("evt-1"),
            values = values,
        )

    private fun rule(
        id: String = "rule-1",
        action: DecisionAction = DecisionAction.CHALLENGE,
        reasonCode: String = "rule_hit",
        condition: RuleCondition = RuleCondition.BooleanEquals("is_new_counterparty", true),
    ): RuleDefinition =
        RuleDefinition(
            id = id,
            action = action,
            reasonCode = ReasonCode(reasonCode),
            condition = condition,
        )
}
