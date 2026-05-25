package com.fraudcontrols.scoring

import com.fraudcontrols.core.EventId
import com.fraudcontrols.core.FeatureSnapshot
import com.fraudcontrols.core.FeatureValue
import com.fraudcontrols.core.RiskBand
import com.fraudcontrols.core.ScoreFactor
import com.fraudcontrols.features.FraudFeatureNames
import kotlin.test.Test
import kotlin.test.assertEquals

class BaselineRiskScorerTest {
    private val scorer = BaselineRiskScorer()

    @Test
    fun `scores low risk transfer`() {
        val score = scorer.score(
            snapshot(
                amount = 25.0,
                ratio = 0.05,
                isNewCounterparty = false,
                senderAccountAgeDays = 120.0,
            ),
        )

        assertEquals(0.05, score.score)
        assertEquals(RiskBand.LOW, score.band)
        assertEquals(emptyList(), score.factors)
        assertEquals(0, score.latencyMs)
    }

    @Test
    fun `scores medium risk transfer with explainable factors`() {
        val score = scorer.score(
            snapshot(
                amount = 1_250.0,
                ratio = 0.30,
                isNewCounterparty = true,
                senderAccountAgeDays = 45.0,
            ),
        )

        assertEquals(0.50, score.score)
        assertEquals(RiskBand.MEDIUM, score.band)
        assertEquals(
            listOf(
                ScoreFactor(FactorNames.AMOUNT, 0.20),
                ScoreFactor(FactorNames.AMOUNT_TO_BALANCE_RATIO, 0.10),
                ScoreFactor(FactorNames.NEW_COUNTERPARTY, 0.15),
            ),
            score.factors,
        )
    }

    @Test
    fun `caps high risk transfer at probability maximum`() {
        val score = scorer.score(
            snapshot(
                amount = 6_000.0,
                ratio = 0.95,
                isNewCounterparty = true,
                senderAccountAgeDays = 0.5,
            ),
        )

        assertEquals(1.0, score.score)
        assertEquals(RiskBand.HIGH, score.band)
    }

    @Test
    fun `treats missing sender balance ratio as risk signal`() {
        val score = scorer.score(
            FeatureSnapshot(
                eventId = EventId("evt-1"),
                values = mapOf(
                    FraudFeatureNames.AMOUNT to FeatureValue.NumberValue(25.0),
                    FraudFeatureNames.AMOUNT_TO_SENDER_BALANCE_RATIO to FeatureValue.Missing("sender balance missing"),
                    FraudFeatureNames.IS_NEW_COUNTERPARTY to FeatureValue.BooleanValue(false),
                    FraudFeatureNames.SENDER_ACCOUNT_AGE_DAYS to FeatureValue.NumberValue(120.0),
                ),
            ),
        )

        assertEquals(0.20, score.score)
        assertEquals(listOf(ScoreFactor(FactorNames.AMOUNT_TO_BALANCE_RATIO, 0.15)), score.factors)
    }

    private fun snapshot(
        amount: Double,
        ratio: Double,
        isNewCounterparty: Boolean,
        senderAccountAgeDays: Double,
    ): FeatureSnapshot =
        FeatureSnapshot(
            eventId = EventId("evt-1"),
            values = mapOf(
                FraudFeatureNames.AMOUNT to FeatureValue.NumberValue(amount),
                FraudFeatureNames.AMOUNT_TO_SENDER_BALANCE_RATIO to FeatureValue.NumberValue(ratio),
                FraudFeatureNames.IS_NEW_COUNTERPARTY to FeatureValue.BooleanValue(isNewCounterparty),
                FraudFeatureNames.SENDER_ACCOUNT_AGE_DAYS to FeatureValue.NumberValue(senderAccountAgeDays),
            ),
        )
}
