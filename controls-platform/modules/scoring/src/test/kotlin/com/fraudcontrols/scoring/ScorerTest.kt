package com.fraudcontrols.scoring

import com.fraudcontrols.core.CustomerId
import com.fraudcontrols.core.DeviceFingerprint
import com.fraudcontrols.core.EventId
import com.fraudcontrols.core.Factor
import com.fraudcontrols.core.FeatureValue
import com.fraudcontrols.core.GeoPoint
import com.fraudcontrols.core.Money
import com.fraudcontrols.core.ScoreResult
import com.fraudcontrols.core.ScoringContext
import com.fraudcontrols.core.TransactionEvent
import com.fraudcontrols.core.TransactionType
import com.fraudcontrols.features.FeatureResolver
import com.fraudcontrols.features.FraudFeatureNames
import com.fraudcontrols.features.defaultEventFeatureProviders
import java.io.StringReader
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScorerContractTest {
    @Test
    fun `rule based scorer emits calibrated probability with factors`() = runTest {
        val scorer = RuleBasedScorer(
            name = "heuristic",
            version = "heuristic",
            featureResolver = FeatureResolver(defaultEventFeatureProviders()),
            config = RuleBasedScorerConfig(
                intercept = -2.0,
                weights = listOf(
                    FeatureWeight(FraudFeatureNames.AMOUNT, weight = 0.001),
                    FeatureWeight(FraudFeatureNames.IS_NEW_COUNTERPARTY, weight = 1.5),
                ),
            ),
        )

        val result = scorer.score(ScoringContext(sampleEvent()))

        assertEquals("heuristic", scorer.name)
        assertEquals("heuristic", result.modelVersion)
        assertEquals(0.383433, result.score.roundTo(6))
        assertEquals(-0.475, result.rawScore?.roundTo(6))
        assertEquals(
            listOf(
                Factor(FraudFeatureNames.AMOUNT, 0.025),
                Factor(FraudFeatureNames.IS_NEW_COUNTERPARTY, 1.5),
            ),
            result.contributingFactors,
        )
        assertTrue(result.latencyMs >= 0.0)
    }

    @Test
    fun `scorer feature provider exposes fraud model score through feature resolution`() = runTest {
        val provider = ScorerFeatureProvider(FixedScorer(name = "model", version = "v1", score = 0.82))
        val result = FeatureResolver(listOf(provider))
            .request(ScoringContext(sampleEvent()))
            .resolve(FraudFeatureNames.FRAUD_MODEL_SCORE)

        assertEquals(0.82, (result as FeatureValue.ScoreValue).value)
        assertEquals("v1", result.result.modelVersion)
    }

    @Test
    fun `xgboost scorer applies platt calibration and maps shap values to factors`() = runTest {
        val scorer = XGBoostScorer(
            name = "xgboost_v1",
            modelId = "fraud_xgb_v1",
            client = StubXGBoostScoreClient(
                rawScore = 2.0,
                shapValues = mapOf("amount" to 0.4, "sender_age" to -0.1),
            ),
            calibrator = PlattCalibrator(slope = 0.5, intercept = -0.25),
        )

        val result = scorer.score(ScoringContext(sampleEvent()))

        assertEquals("fraud_xgb_v1", result.modelVersion)
        assertEquals(2.0, result.rawScore)
        assertEquals(0.679179, result.score.roundTo(6))
        assertEquals(
            listOf(Factor("amount", 0.4), Factor("sender_age", -0.1)),
            result.contributingFactors,
        )
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `shadow scorer fans out to shadows and returns primary score`() = runTest {
        val sink = RecordingShadowSink()
        val scorer = ShadowScorer(
            name = "shadow_wrapped_xgb",
            primary = DelayedScorer("primary", "v1", score = 0.7, delayMs = 100),
            shadows = listOf(
                DelayedScorer("candidate", "v2", score = 0.9, delayMs = 100),
            ),
            sink = sink,
        )

        val start = currentTime
        val result = scorer.score(ScoringContext(sampleEvent()))

        assertEquals(100, currentTime - start)
        assertEquals(0.7, result.score)
        assertEquals(
            listOf(ShadowScorerRole.PRIMARY, ShadowScorerRole.SHADOW),
            sink.evaluations.map { it.role },
        )
        assertEquals(listOf("primary", "candidate"), sink.evaluations.map { it.scorerName })
    }

    @Test
    fun `shadow scorer records shadow failures without failing primary`() = runTest {
        val sink = RecordingShadowSink()
        val scorer = ShadowScorer(
            name = "shadow_wrapped_xgb",
            primary = FixedScorer("primary", "v1", 0.7),
            shadows = listOf(FailingScorer("candidate", "v2")),
            sink = sink,
        )

        val result = scorer.score(ScoringContext(sampleEvent()))

        assertEquals(0.7, result.score)
        assertEquals("candidate failed", sink.evaluations.single { it.role == ShadowScorerRole.SHADOW }.error)
    }

    @Test
    fun `failover scorer returns degraded fallback on timeout`() = runTest {
        val scorer = FailoverScorer(
            name = "primary_scorer",
            version = "primary_scorer",
            primary = DelayedScorer("primary", "v1", score = 0.9, delayMs = 100),
            fallback = FixedScorer("fallback", "v1", score = 0.2),
            timeout = Duration.ofMillis(30),
        )

        val result = scorer.score(ScoringContext(sampleEvent()))

        assertEquals(0.2, result.score)
        assertEquals(true, result.degraded)
        assertEquals("v1", result.modelVersion)
    }

    @Test
    fun `failover scorer does not convert cancellation into degraded fallback`() = runTest {
        val scorer = FailoverScorer(
            name = "primary_scorer",
            version = "primary_scorer",
            primary = CancellingScorer("primary", "v1"),
            fallback = FixedScorer("fallback", "v1", score = 0.2),
            timeout = Duration.ofMillis(30),
        )

        assertFailsWith<CancellationException> {
            scorer.score(ScoringContext(sampleEvent()))
        }
    }

    @Test
    fun `score result rejects invalid score metadata`() {
        assertFailsWith<IllegalArgumentException> {
            ScoreResult(
                score = 0.5,
                rawScore = null,
                contributingFactors = listOf(Factor("bad", Double.NaN)),
                modelVersion = "v1",
                latencyMs = 1.0,
            )
        }
    }
}

class ScoringConfigTest {
    @Test
    fun `loads scoring yaml and wires named scorer graph`() = runTest {
        val config = ScoringConfigLoader().load(
            StringReader(
                """
                scoring:
                  features:
                    - name: fraud_model_score
                      provider: scorer
                      scorer: primary_scorer
                  scorers:
                    - name: primary_scorer
                      type: failover
                      primary: shadow_wrapped_xgb
                      fallback: heuristic
                      timeout_ms: 30
                    - name: shadow_wrapped_xgb
                      type: shadow
                      primary: xgboost_v1
                      shadows: [xgboost_v2_candidate]
                    - name: xgboost_v1
                      type: xgboost
                      sidecar_address: scoring-sidecar:50051
                      model_id: fraud_xgb_v1
                    - name: xgboost_v2_candidate
                      type: xgboost
                      sidecar_address: scoring-sidecar:50051
                      model_id: fraud_xgb_v2
                    - name: heuristic
                      type: rule_based
                      config_path: configs/heuristic.yaml
                """.trimIndent(),
            ),
        )
        val factory = ScorerFactory(
            featureResolver = FeatureResolver(defaultEventFeatureProviders()),
            ruleBasedConfigsByPath = mapOf(
                "configs/heuristic.yaml" to RuleBasedScorerConfig(
                    intercept = 0.0,
                    weights = listOf(FeatureWeight(FraudFeatureNames.AMOUNT, 0.001)),
                ),
            ),
            xgBoostClientFactory = { StubXGBoostScoreClient(rawScore = 1.0) },
        )

        val scorers = factory.build(config)
        val result = scorers.getValue("primary_scorer").score(ScoringContext(sampleEvent()))

        assertEquals(setOf("primary_scorer", "shadow_wrapped_xgb", "xgboost_v1", "xgboost_v2_candidate", "heuristic"), scorers.keys)
        assertEquals("fraud_xgb_v1", result.modelVersion)
        assertEquals(false, result.degraded)
    }

    @Test
    fun `loads rule based scorer weights from yaml`() {
        val config = RuleBasedScorerConfigLoader().load(
            StringReader(
                """
                rule_based:
                  intercept: -2.0
                  weights:
                    - feature: amount
                      weight: 0.001
                    - feature: is_new_counterparty
                      weight: 1.5
                      missing_value: 0.0
                """.trimIndent(),
            ),
        )

        assertEquals(
            RuleBasedScorerConfig(
                intercept = -2.0,
                weights = listOf(
                    FeatureWeight(FraudFeatureNames.AMOUNT, 0.001),
                    FeatureWeight(FraudFeatureNames.IS_NEW_COUNTERPARTY, 1.5, missingValue = 0.0),
                ),
            ),
            config,
        )
    }
}

private class FixedScorer(
    override val name: String,
    override val version: String,
    private val score: Double,
) : Scorer {
    override suspend fun score(context: ScoringContext): ScoreResult =
        ScoreResult(
            score = score,
            rawScore = score,
            contributingFactors = emptyList(),
            modelVersion = version,
            latencyMs = 0.0,
        )
}

private class DelayedScorer(
    override val name: String,
    override val version: String,
    private val score: Double,
    private val delayMs: Long,
) : Scorer {
    override suspend fun score(context: ScoringContext): ScoreResult {
        delay(delayMs)
        return ScoreResult(
            score = score,
            rawScore = score,
            contributingFactors = emptyList(),
            modelVersion = version,
            latencyMs = delayMs.toDouble(),
        )
    }
}

private class FailingScorer(
    override val name: String,
    override val version: String,
) : Scorer {
    override suspend fun score(context: ScoringContext): ScoreResult =
        error("$name failed")
}

private class CancellingScorer(
    override val name: String,
    override val version: String,
) : Scorer {
    override suspend fun score(context: ScoringContext): ScoreResult =
        throw CancellationException("$name cancelled")
}

private class RecordingShadowSink : ShadowEvaluationSink {
    val evaluations = mutableListOf<ShadowEvaluation>()

    override suspend fun record(evaluations: List<ShadowEvaluation>) {
        this.evaluations += evaluations
    }
}

private fun sampleEvent(): TransactionEvent =
    TransactionEvent(
        eventId = EventId("evt-1"),
        timestamp = Instant.parse("2026-01-01T00:00:00Z"),
        senderId = CustomerId("sender-1"),
        recipientId = CustomerId("recipient-1"),
        amount = Money.usd("25.00"),
        transactionType = TransactionType.P2P_SEND,
        senderDeviceFingerprint = DeviceFingerprint("device-1"),
        senderGeo = GeoPoint(latitude = 43.6532, longitude = -79.3832),
        senderBalanceBefore = BigDecimal("100.00"),
        senderBalanceAfter = BigDecimal("75.00"),
        recipientBalanceBefore = BigDecimal("50.00"),
        recipientBalanceAfter = BigDecimal("75.00"),
        senderAccountAgeDays = 0.125,
        recipientAccountAgeDays = 120.5,
        isNewCounterparty = true,
    )

private fun Double.roundTo(places: Int): Double {
    val scale = Math.pow(10.0, places.toDouble())
    return kotlin.math.round(this * scale) / scale
}
