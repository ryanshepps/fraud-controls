package com.fraudcontrols.streaming

import com.fraudcontrols.core.Decision
import com.fraudcontrols.decisioning.contracts.toDecisionEventJsonString
import com.fraudcontrols.decisioning.contracts.toRuleEvaluationEventJsonString
import com.fraudcontrols.rules.RuleEvaluationResult

fun Decision.toDecisionJson(): String =
    toDecisionEventJsonString()

fun RuleEvaluationResult.toRuleEvaluationJson(): String =
    toRuleEvaluationEventJsonString()
