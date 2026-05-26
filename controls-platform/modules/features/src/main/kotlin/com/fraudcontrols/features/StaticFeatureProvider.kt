package com.fraudcontrols.features

import com.fraudcontrols.core.FeatureValue
import com.fraudcontrols.core.ScoringContext

class StaticFeatureProvider(
    override val featureName: String,
    private val value: FeatureValue,
) : FeatureProvider {
    override suspend fun compute(context: ScoringContext): FeatureValue = value
}
