package com.fraudcontrols.core

import java.time.Instant

data class Decision(
    val eventId: EventId,
    val action: DecisionAction,
    val reasonCodes: List<ReasonCode>,
    val score: ScoreResult,
    val ruleEvaluationIds: List<String>,
    val decidedAt: Instant,
) {
    init {
        require(ruleEvaluationIds.none { it.isBlank() }) { "rule evaluation ids must not be blank" }
    }
}

enum class DecisionAction {
    ALLOW,
    CHALLENGE,
    HOLD,
    DENY,
}

@JvmInline
value class ReasonCode(val value: String) {
    init {
        require(value.isNotBlank()) { "reason code must not be blank" }
    }
}

data class ScoreResult(
    val score: Double,
    val band: RiskBand,
    val factors: List<ScoreFactor>,
    val latencyMs: Long,
) {
    init {
        require(score in 0.0..1.0) { "score must be in [0.0, 1.0]" }
        require(latencyMs >= 0) { "latency cannot be negative" }
    }
}

enum class RiskBand {
    LOW,
    MEDIUM,
    HIGH,
}

data class ScoreFactor(
    val name: String,
    val contribution: Double,
) {
    init {
        require(name.isNotBlank()) { "score factor name must not be blank" }
        require(contribution.isFinite()) { "score factor contribution must be finite" }
    }
}
