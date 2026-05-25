package com.fraudcontrols.core

sealed interface FeatureValue {
    data class NumberValue(val value: Double) : FeatureValue {
        init {
            require(value.isFinite()) { "numeric feature value must be finite" }
        }
    }
    data class BooleanValue(val value: Boolean) : FeatureValue
    data class TextValue(val value: String) : FeatureValue
    data class SetValue(val values: Set<String>) : FeatureValue
    data class Unavailable(val reason: String) : FeatureValue {
        init {
            require(reason.isNotBlank()) { "unavailable feature reason must not be blank" }
        }
    }
    data class Missing(val reason: String) : FeatureValue {
        init {
            require(reason.isNotBlank()) { "missing feature reason must not be blank" }
        }
    }
}

data class FeatureSnapshot(
    val eventId: EventId,
    val values: Map<String, FeatureValue>,
) {
    init {
        require(values.keys.none { it.isBlank() }) { "feature names must not be blank" }
    }
}
