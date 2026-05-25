package com.fraudcontrols.streaming

import com.fraudcontrols.decisioning.DecisionEngine
import com.fraudcontrols.decisioning.DecisioningResult
import com.fraudcontrols.rules.RuleDefinition
import java.time.Instant

class FraudgenDecisionProcessor(
    private val parser: FraudgenEventParser = FraudgenEventParser(),
    private val decisionEngine: DecisionEngine = DecisionEngine(),
) {
    fun process(
        payload: String,
        rules: List<RuleDefinition>,
        decidedAt: Instant,
    ): DecisioningResult {
        val event = parser.parse(payload)
        return decisionEngine.decide(
            event = event,
            rules = rules,
            decidedAt = decidedAt,
        )
    }
}
