package com.fraudcontrols.scoring

import com.fraudcontrols.core.FeatureSnapshot
import com.fraudcontrols.core.FeatureValue
import com.fraudcontrols.core.RiskBand
import com.fraudcontrols.core.ScoreFactor
import com.fraudcontrols.core.ScoreResult
import com.fraudcontrols.features.FraudFeatureNames
import kotlin.math.min

class BaselineRiskScorer {
    fun score(features: FeatureSnapshot): ScoreResult {
        val factors = buildList {
            addFactor(FactorNames.AMOUNT, amountContribution(features.number(FraudFeatureNames.AMOUNT)))
            addFactor(
                FactorNames.AMOUNT_TO_BALANCE_RATIO,
                ratioContribution(features.number(FraudFeatureNames.AMOUNT_TO_SENDER_BALANCE_RATIO)),
            )
            addFactor(FactorNames.NEW_COUNTERPARTY, newCounterpartyContribution(features.boolean(FraudFeatureNames.IS_NEW_COUNTERPARTY)))
            addFactor(FactorNames.SENDER_ACCOUNT_AGE, accountAgeContribution(features.number(FraudFeatureNames.SENDER_ACCOUNT_AGE_DAYS)))
        }

        val score = min(BASE_SCORE + factors.sumOf { it.contribution }, MAX_SCORE).roundToScorePrecision()
        return ScoreResult(
            score = score,
            band = bandFor(score),
            factors = factors,
            latencyMs = 0,
        )
    }

    private fun amountContribution(amount: Double?): Double =
        when {
            amount == null -> 0.0
            amount >= 5_000.0 -> 0.30
            amount >= 1_000.0 -> 0.20
            amount >= 100.0 -> 0.10
            else -> 0.0
        }

    private fun ratioContribution(ratio: Double?): Double =
        when {
            ratio == null -> 0.15
            ratio >= 0.8 -> 0.30
            ratio >= 0.5 -> 0.20
            ratio >= 0.2 -> 0.10
            else -> 0.0
        }

    private fun newCounterpartyContribution(isNewCounterparty: Boolean?): Double =
        if (isNewCounterparty == true) 0.15 else 0.0

    private fun accountAgeContribution(ageDays: Double?): Double =
        when {
            ageDays == null -> 0.0
            ageDays < 1.0 -> 0.25
            ageDays < 7.0 -> 0.15
            ageDays < 30.0 -> 0.05
            else -> 0.0
        }

    private fun bandFor(score: Double): RiskBand =
        when {
            score >= HIGH_THRESHOLD -> RiskBand.HIGH
            score >= MEDIUM_THRESHOLD -> RiskBand.MEDIUM
            else -> RiskBand.LOW
        }

    private fun MutableList<ScoreFactor>.addFactor(
        name: String,
        contribution: Double,
    ) {
        if (contribution > 0.0) {
            add(ScoreFactor(name, contribution))
        }
    }

    private fun FeatureSnapshot.number(name: String): Double? =
        when (val value = values[name]) {
            is FeatureValue.NumberValue -> value.value
            else -> null
        }

    private fun FeatureSnapshot.boolean(name: String): Boolean? =
        when (val value = values[name]) {
            is FeatureValue.BooleanValue -> value.value
            else -> null
        }

    private fun Double.roundToScorePrecision(): Double =
        kotlin.math.round(this * SCORE_SCALE) / SCORE_SCALE

    private companion object {
        const val BASE_SCORE = 0.05
        const val MAX_SCORE = 1.0
        const val MEDIUM_THRESHOLD = 0.35
        const val HIGH_THRESHOLD = 0.70
        const val SCORE_SCALE = 1_000_000.0
    }
}

object FactorNames {
    const val AMOUNT = "amount"
    const val AMOUNT_TO_BALANCE_RATIO = "amount_to_sender_balance_ratio"
    const val NEW_COUNTERPARTY = "new_counterparty"
    const val SENDER_ACCOUNT_AGE = "sender_account_age"
}
