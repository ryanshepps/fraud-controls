package com.fraudcontrols.scoring

import com.fraudcontrols.features.FeatureResolver
import java.io.Reader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

data class ScoringConfig(
    val features: List<ScoringFeatureBinding>,
    val scorers: List<ScorerDefinition>,
) {
    init {
        require(scorers.isNotEmpty()) { "scoring config must include at least one scorer" }
        require(scorers.map { it.name }.toSet().size == scorers.size) {
            "scorer names must be unique"
        }
    }
}

data class ScoringFeatureBinding(
    val name: String,
    val provider: String,
    val scorer: String,
) {
    init {
        require(name.isNotBlank()) { "scoring feature name must not be blank" }
        require(provider == "scorer") { "only scorer feature provider is supported" }
        require(scorer.isNotBlank()) { "scoring feature scorer must not be blank" }
    }
}

data class ScorerDefinition(
    val name: String,
    val type: ScorerType,
    val primary: String? = null,
    val fallback: String? = null,
    val shadows: List<String> = emptyList(),
    val timeoutMs: Long? = null,
    val sidecarAddress: String? = null,
    val modelId: String? = null,
    val configPath: String? = null,
) {
    init {
        require(name.isNotBlank()) { "scorer name must not be blank" }
        timeoutMs?.let { require(it > 0) { "scorer timeout must be positive" } }
        require(shadows.none { it.isBlank() }) { "shadow scorer references must not be blank" }
    }
}

enum class ScorerType {
    FAILOVER,
    SHADOW,
    XGBOOST,
    RULE_BASED,
}

class ScoringConfigLoader {
    fun load(path: Path): ScoringConfig =
        Files.newBufferedReader(path).use(::load)

    fun load(reader: Reader): ScoringConfig {
        val root = Load(LoadSettings.builder().build()).loadFromReader(reader).asMap()
        val scoring = root.requiredMap("scoring")
        return ScoringConfig(
            features = scoring.requiredList("features").map { it.asMap().toFeatureBinding() },
            scorers = scoring.requiredList("scorers").map { it.asMap().toScorerDefinition() },
        )
    }

    private fun Map<String, Any?>.toFeatureBinding(): ScoringFeatureBinding =
        ScoringFeatureBinding(
            name = requiredString("name"),
            provider = requiredString("provider"),
            scorer = requiredString("scorer"),
        )

    private fun Map<String, Any?>.toScorerDefinition(): ScorerDefinition =
        ScorerDefinition(
            name = requiredString("name"),
            type = ScorerType.valueOf(requiredString("type").uppercase()),
            primary = optionalString("primary"),
            fallback = optionalString("fallback"),
            shadows = optionalList("shadows").map { it.toString() },
            timeoutMs = optionalLong("timeout_ms"),
            sidecarAddress = optionalString("sidecar_address"),
            modelId = optionalString("model_id"),
            configPath = optionalString("config_path"),
        )
}

class RuleBasedScorerConfigLoader {
    fun load(path: Path): RuleBasedScorerConfig =
        Files.newBufferedReader(path).use(::load)

    fun load(reader: Reader): RuleBasedScorerConfig {
        val root = Load(LoadSettings.builder().build()).loadFromReader(reader).asMap()
        val ruleBased = root["rule_based"]?.asMap() ?: root
        return RuleBasedScorerConfig(
            intercept = ruleBased.requiredDouble("intercept"),
            weights = ruleBased.requiredList("weights").map { it.asMap().toFeatureWeight() },
        )
    }

    private fun Map<String, Any?>.toFeatureWeight(): FeatureWeight =
        FeatureWeight(
            featureName = requiredString("feature"),
            weight = requiredDouble("weight"),
            missingValue = optionalDouble("missing_value") ?: 0.0,
        )
}

class ScorerFactory(
    private val featureResolver: FeatureResolver,
    private val ruleBasedConfigsByPath: Map<String, RuleBasedScorerConfig>,
    private val xgBoostClientFactory: (ScorerDefinition) -> XGBoostScoreClient = {
        StubXGBoostScoreClient()
    },
    private val shadowEvaluationSink: ShadowEvaluationSink = NoopShadowEvaluationSink,
) {
    fun build(config: ScoringConfig): Map<String, Scorer> {
        val definitions = config.scorers.associateBy { it.name }
        val scorers = mutableMapOf<String, Scorer>()
        val visiting = mutableSetOf<String>()

        fun buildScorer(name: String): Scorer {
            scorers[name]?.let { return it }
            require(visiting.add(name)) { "scorer dependency cycle detected at $name" }
            val definition = definitions[name] ?: error("unknown scorer: $name")
            val scorer = when (definition.type) {
                ScorerType.RULE_BASED -> buildRuleBased(definition)
                ScorerType.XGBOOST -> buildXGBoost(definition)
                ScorerType.SHADOW -> buildShadow(definition, ::buildScorer)
                ScorerType.FAILOVER -> buildFailover(definition, ::buildScorer)
            }
            visiting.remove(name)
            scorers[name] = scorer
            return scorer
        }

        for (definition in config.scorers) {
            buildScorer(definition.name)
        }
        for (binding in config.features) {
            require(binding.scorer in scorers) { "feature ${binding.name} references unknown scorer ${binding.scorer}" }
        }

        return scorers
    }

    private fun buildRuleBased(definition: ScorerDefinition): Scorer {
        val configPath = definition.configPath
            ?: error("rule_based scorer ${definition.name} requires config_path")
        val scorerConfig = ruleBasedConfigsByPath[configPath]
            ?: error("missing rule_based config for $configPath")
        return RuleBasedScorer(
            name = definition.name,
            version = definition.name,
            featureResolver = featureResolver,
            config = scorerConfig,
        )
    }

    private fun buildXGBoost(definition: ScorerDefinition): Scorer =
        XGBoostScorer(
            name = definition.name,
            modelId = definition.modelId ?: error("xgboost scorer ${definition.name} requires model_id"),
            client = xgBoostClientFactory(definition),
        )

    private fun buildShadow(
        definition: ScorerDefinition,
        buildScorer: (String) -> Scorer,
    ): Scorer =
        ShadowScorer(
            name = definition.name,
            primary = buildScorer(definition.primary ?: error("shadow scorer ${definition.name} requires primary")),
            shadows = definition.shadows.map(buildScorer),
            sink = shadowEvaluationSink,
        )

    private fun buildFailover(
        definition: ScorerDefinition,
        buildScorer: (String) -> Scorer,
    ): Scorer =
        FailoverScorer(
            name = definition.name,
            version = definition.name,
            primary = buildScorer(definition.primary ?: error("failover scorer ${definition.name} requires primary")),
            fallback = buildScorer(definition.fallback ?: error("failover scorer ${definition.name} requires fallback")),
            timeout = Duration.ofMillis(definition.timeoutMs ?: 30),
        )
}

@Suppress("UNCHECKED_CAST")
private fun Any?.asMap(): Map<String, Any?> =
    this as? Map<String, Any?> ?: error("expected YAML object")

private fun Map<String, Any?>.requiredMap(key: String): Map<String, Any?> =
    this[key].asMap()

private fun Map<String, Any?>.requiredList(key: String): List<Any?> =
    this[key] as? List<Any?>
        ?: error("missing or invalid YAML list: $key")

private fun Map<String, Any?>.optionalList(key: String): List<Any?> =
    this[key] as? List<Any?> ?: emptyList()

private fun Map<String, Any?>.requiredString(key: String): String =
    optionalString(key) ?: error("missing required YAML string: $key")

private fun Map<String, Any?>.optionalString(key: String): String? =
    this[key]?.toString()

private fun Map<String, Any?>.optionalLong(key: String): Long? =
    when (val value = this[key]) {
        null -> null
        is Number -> value.toLong()
        else -> value.toString().toLong()
    }

private fun Map<String, Any?>.requiredDouble(key: String): Double =
    optionalDouble(key) ?: error("missing required YAML number: $key")

private fun Map<String, Any?>.optionalDouble(key: String): Double? =
    when (val value = this[key]) {
        null -> null
        is Number -> value.toDouble()
        else -> value.toString().toDouble()
    }
