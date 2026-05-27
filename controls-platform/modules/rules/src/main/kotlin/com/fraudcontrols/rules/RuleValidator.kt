package com.fraudcontrols.rules

class RuleValidator(
    knownFeatureNames: Set<String>,
) {
    private val knownFeatureNames = knownFeatureNames.toSet()

    init {
        require(this.knownFeatureNames.isNotEmpty()) { "known feature names must not be empty" }
        require(this.knownFeatureNames.none { it.isBlank() }) { "known feature names must not be blank" }
    }

    fun validate(ruleSet: RuleSet): RuleSet {
        val errors = ruleSet.rules.flatMap { rule ->
            rule.condition.featureNames()
                .filterNot { it in knownFeatureNames }
                .map { featureName -> "${rule.id}: unknown feature: $featureName" }
        }
        if (errors.isNotEmpty()) {
            throw RuleValidationException(errors.joinToString("; "))
        }
        return ruleSet
    }
}

class RuleValidationException(message: String) : IllegalArgumentException(message)
