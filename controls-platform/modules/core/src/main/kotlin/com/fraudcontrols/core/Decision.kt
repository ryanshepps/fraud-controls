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
    val rawScore: Double?,
    val contributingFactors: List<Factor>,
    val modelVersion: String,
    val latencyMs: Double,
    val degraded: Boolean = false,
) {
    init {
        require(score in 0.0..1.0) { "score must be in [0.0, 1.0]" }
        require(rawScore == null || rawScore.isFinite()) { "raw score must be finite when present" }
        require(modelVersion.isNotBlank()) { "model version must not be blank" }
        require(latencyMs.isFinite() && latencyMs >= 0.0) { "latency cannot be negative or non-finite" }
    }
}

data class Factor(
    val name: String,
    val contribution: Double,
) {
    init {
        require(name.isNotBlank()) { "score factor name must not be blank" }
        require(contribution.isFinite()) { "score factor contribution must be finite" }
    }
}
