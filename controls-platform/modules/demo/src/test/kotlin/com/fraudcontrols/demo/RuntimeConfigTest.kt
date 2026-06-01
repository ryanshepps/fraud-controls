package com.fraudcontrols.demo

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuntimeConfigTest {
    @Test
    fun `loads checked in runtime configs`() {
        val applicationPath = findCheckedInApplicationConfig()

        val bundle = RuntimeConfigLoader(
            applicationConfigLoader = ApplicationConfigLoader(
                mapOf("APPLICATION_CONFIG_PATH" to applicationPath.toString())::get,
            ),
        ).load()

        assertEquals(
            setOf("demo-new-account-cashout", "demo-velocity-review", "demo-score-shadow"),
            bundle.initialRules.map { it.id }.toSet(),
        )
        assertEquals(setOf("configs/heuristic.yaml"), bundle.ruleBasedConfigsByPath.keys)
        val shadowScorer = bundle.scoring.scorers.single { it.name == "shadow_wrapped_xgb" }
        assertEquals("demo_sidecar", shadowScorer.primary)
        assertEquals(listOf("demo_candidate"), shadowScorer.shadows)
    }

    @Test
    fun `loads runtime config from yaml and applies environment overrides`() {
        val configDir = createConfigFixture()
        val applicationPath = configDir.resolve("application.yaml")
        val env = mapOf(
            "APPLICATION_CONFIG_PATH" to applicationPath.toString(),
            "HTTP_PORT" to "19090",
            "SCORING_SIDECAR_URL" to "http://scoring-sidecar:50051/score",
        )

        val bundle = RuntimeConfigLoader(
            applicationConfigLoader = ApplicationConfigLoader(env::get),
        ).load()

        assertEquals("controls-platform", bundle.application.serviceName)
        assertEquals(19090, bundle.application.httpPort)
        assertEquals("decision_side_effects", bundle.application.decisionSideEffectsTopic)
        assertEquals(listOf("demo-score-shadow"), bundle.initialRules.map { it.id })
        assertEquals(setOf("heuristic.yaml"), bundle.ruleBasedConfigsByPath.keys)
        val sidecarScorer = bundle.scoring.scorers.single { it.name == "demo_sidecar" }
        assertEquals("http://scoring-sidecar:50051/score", sidecarScorer.sidecarAddress)
    }

    @Test
    fun `fails fast when scoring config references a missing scorer`() {
        val configDir = createConfigFixture(
            scoringYaml = """
                scoring:
                  features:
                    - name: fraud_model_score
                      provider: scorer
                      scorer: missing_scorer
                  scorers:
                    - name: xgboost_v1
                      type: xgboost
                      sidecar_address: http://localhost:50051/score
                      model_id: fraud_xgb_v1
            """.trimIndent(),
        )

        val error = assertFailsWith<RuntimeConfigException> {
            RuntimeConfigLoader(
                applicationConfigLoader = ApplicationConfigLoader(
                    mapOf("APPLICATION_CONFIG_PATH" to configDir.resolve("application.yaml").toString())::get,
                ),
            ).load()
        }

        assertTrue(error.message.orEmpty().contains("references unknown scorer"))
    }

    @Test
    fun `fails fast when application config is invalid`() {
        val configDir = createTempDirectory("runtime-config-test").resolve("configs").createDirectories()
        val applicationPath = configDir.resolve("application.yaml")
        applicationPath.writeText(
            """
            service:
              name: controls-platform
              environment: local
              httpPort: 0
            kafka: {}
            dynamodb: {}
            redis:
              endpoint: redis://localhost:6379
            observability:
              metricsPath: /metrics
            """.trimIndent(),
        )

        val error = assertFailsWith<RuntimeConfigException> {
            ApplicationConfigLoader(
                mapOf("APPLICATION_CONFIG_PATH" to applicationPath.toString())::get,
            ).load()
        }

        assertTrue(error.message.orEmpty().contains("HTTP_PORT must be between 1 and 65535"))
    }

    @Test
    fun `fails fast when configured rules reference unknown features`() {
        val configDir = createConfigFixture(
            rulesYaml = """
                version: 1
                rules:
                  - id: bad-rule
                    version: 1
                    description: Invalid feature.
                    enabled: true
                    mode: enforce
                    priority: 1
                    when:
                      feature: not_a_feature
                      op: gte
                      value: 1
                    action:
                      type: block
                      reason_code: invalid_feature
            """.trimIndent(),
        )

        val error = assertFailsWith<RuntimeConfigException> {
            RuntimeConfigLoader(
                applicationConfigLoader = ApplicationConfigLoader(
                    mapOf("APPLICATION_CONFIG_PATH" to configDir.resolve("application.yaml").toString())::get,
                ),
            ).load()
        }

        assertTrue(error.message.orEmpty().contains("invalid rule config"))
    }

    private fun createConfigFixture(
        scoringYaml: String = defaultScoringYaml(),
        rulesYaml: String = defaultRulesYaml(),
    ) = createTempDirectory("runtime-config-test").resolve("configs").createDirectories().also { configDir ->
        configDir.resolve("rules").createDirectories()
        configDir.resolve("application.yaml").writeText(defaultApplicationYaml())
        configDir.resolve("scoring.yaml").writeText(scoringYaml)
        configDir.resolve("heuristic.yaml").writeText(defaultHeuristicYaml())
        configDir.resolve("rules").resolve("local.yaml").writeText(rulesYaml)
    }

    private fun defaultApplicationYaml(): String =
        """
        service:
          name: controls-platform
          environment: local
          httpHost: 0.0.0.0
          httpPort: 8080
        configs:
          scoring: scoring.yaml
          rulesDirectory: rules
        kafka:
          bootstrapServers: localhost:19092
          inputTopic: transactions
          decisionsTopic: controls.decisions
          ruleEvaluationsTopic: rule_evaluations
          decisionSideEffectsTopic: decision_side_effects
          shadowEvaluationsTopic: shadow_evaluations
          ruleChangesTopic: rule_changes
          fraudLabelsTopic: fraud_labels
        dynamodb:
          endpoint: http://localhost:18000
          decisionsTable: controls_decisions
        redis:
          endpoint: redis://localhost:6379
        observability:
          metricsPath: /metrics
        """.trimIndent()

    private fun defaultScoringYaml(): String =
        """
        scoring:
          features:
            - name: fraud_model_score
              provider: scorer
              scorer: primary_scorer
          scorers:
            - name: primary_scorer
              type: failover
              primary: demo_sidecar
              fallback: heuristic
              timeout_ms: 30
            - name: demo_sidecar
              type: xgboost
              sidecar_address: http://localhost:50051/score
              model_id: deterministic-demo-v1
            - name: heuristic
              type: rule_based
              config_path: heuristic.yaml
        """.trimIndent()

    private fun defaultHeuristicYaml(): String =
        """
        rule_based:
          intercept: -2.0
          weights:
            - feature: amount
              weight: 0.001
              missing_value: 0.0
        """.trimIndent()

    private fun defaultRulesYaml(): String =
        """
        version: 1
        rules:
          - id: demo-score-shadow
            version: 1
            description: Shadow-only score threshold.
            enabled: true
            mode: shadow
            priority: 150
            when:
              feature: fraud_model_score
              op: gte
              value: 0.55
            action:
              type: block
              reason_code: demo_score_shadow
        """.trimIndent()

    private fun findCheckedInApplicationConfig(): Path {
        val workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        return generateSequence(workingDirectory) { it.parent }
            .map { it.resolve("configs").resolve("application.yaml") }
            .firstOrNull(Files::exists)
            ?: error("could not find checked-in configs/application.yaml from $workingDirectory")
    }
}
