package com.fraudcontrols.demo

import com.fraudcontrols.features.FraudFeatureNames
import com.fraudcontrols.rules.RuleConfigException
import com.fraudcontrols.rules.RuleConfigLoader
import com.fraudcontrols.rules.RuleDefinition
import com.fraudcontrols.rules.RuleValidationException
import com.fraudcontrols.rules.RuleValidator
import com.fraudcontrols.scoring.RuleBasedScorerConfig
import com.fraudcontrols.scoring.RuleBasedScorerConfigLoader
import com.fraudcontrols.scoring.ScorerType
import com.fraudcontrols.scoring.ScoringConfig
import com.fraudcontrols.scoring.ScoringConfigLoader
import java.io.Reader
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

internal data class DemoRuntimeConfig(
    val serviceName: String,
    val environment: String,
    val httpHost: String,
    val httpPort: Int,
    val metricsPath: String,
    val kafkaBootstrapServers: String,
    val transactionsTopic: String,
    val decisionsTopic: String,
    val ruleEvaluationsTopic: String,
    val shadowEvaluationsTopic: String,
    val ruleChangesTopic: String,
    val fraudLabelsTopic: String,
    val dynamoEndpoint: String,
    val auditTable: String,
    val redisEndpoint: URI,
    val scoringConfigPath: Path,
    val rulesDirectory: Path,
    val scoringSidecarUrlOverride: String?,
) {
    val redisHost: String
        get() = redisEndpoint.host ?: throw RuntimeConfigException("redis endpoint must include a host")

    val redisPort: Int
        get() {
            val port = redisEndpoint.port
            return if (port > 0) port else 6379
        }
}

internal class ApplicationConfigLoader(
    private val env: (String) -> String? = System::getenv,
) {
    fun load(path: Path = defaultApplicationPath()): DemoRuntimeConfig {
        val configDir = path.parent ?: Path.of(".")
        if (!path.exists()) {
            throw RuntimeConfigException("application config not found: $path")
        }
        return Files.newBufferedReader(path).use { load(it, configDir) }
    }

    fun load(
        reader: Reader,
        configDir: Path = Path.of("configs"),
    ): DemoRuntimeConfig {
        val root = try {
            Load(LoadSettings.builder().build()).loadFromReader(reader).asMap("application config")
        } catch (error: RuntimeException) {
            throw RuntimeConfigException("invalid application config YAML: ${error.message}", error)
        }
        val service = root.requiredMap("service")
        val kafka = root.requiredMap("kafka")
        val dynamo = root.requiredMap("dynamodb")
        val redis = root.requiredMap("redis")
        val observability = root.requiredMap("observability")
        val configs = root.map("configs").orEmpty()

        val redisEndpoint = env("REDIS_ENDPOINT")
            ?: redis.requiredString("endpoint")
                .let { configured ->
                    val host = env("REDIS_HOST")
                    val port = env("REDIS_PORT")
                    if (host == null && port == null) {
                        configured
                    } else {
                        val configuredUri = configured.toUri("redis.endpoint")
                        "redis://${host ?: configuredUri.host}:${port ?: configuredUri.port.takeIf { it > 0 } ?: 6379}"
                    }
                }

        return DemoRuntimeConfig(
            serviceName = service.requiredString("name"),
            environment = env("SERVICE_ENVIRONMENT") ?: service.requiredString("environment"),
            httpHost = env("HTTP_HOST") ?: service.string("httpHost") ?: "0.0.0.0",
            httpPort = (env("HTTP_PORT") ?: service.requiredString("httpPort")).toPort("HTTP_PORT"),
            metricsPath = (env("METRICS_PATH") ?: observability.requiredString("metricsPath")).toRoutePath("metricsPath"),
            kafkaBootstrapServers = env("KAFKA_BOOTSTRAP_SERVERS") ?: kafka.requiredString("bootstrapServers"),
            transactionsTopic = env("TRANSACTIONS_TOPIC") ?: kafka.requiredString("inputTopic"),
            decisionsTopic = env("DECISIONS_TOPIC") ?: kafka.requiredString("decisionsTopic"),
            ruleEvaluationsTopic = env("RULE_EVALUATIONS_TOPIC") ?: kafka.requiredString("ruleEvaluationsTopic"),
            shadowEvaluationsTopic = env("SHADOW_EVALUATIONS_TOPIC") ?: kafka.requiredString("shadowEvaluationsTopic"),
            ruleChangesTopic = env("RULE_CHANGES_TOPIC") ?: kafka.requiredString("ruleChangesTopic"),
            fraudLabelsTopic = env("FRAUD_LABELS_TOPIC") ?: kafka.requiredString("fraudLabelsTopic"),
            dynamoEndpoint = env("DYNAMODB_ENDPOINT") ?: dynamo.requiredString("endpoint"),
            auditTable = env("DYNAMODB_DECISIONS_TABLE") ?: dynamo.requiredString("decisionsTable"),
            redisEndpoint = redisEndpoint.toUri("redis.endpoint"),
            scoringConfigPath = (env("SCORING_CONFIG_PATH") ?: configs.string("scoring") ?: "scoring.yaml")
                .resolveAgainst(configDir),
            rulesDirectory = (env("RULES_CONFIG_DIR") ?: configs.string("rulesDirectory") ?: "rules")
                .resolveAgainst(configDir),
            scoringSidecarUrlOverride = env("SCORING_SIDECAR_URL")?.takeIf { it.isNotBlank() },
        )
    }

    private fun defaultApplicationPath(): Path =
        env("APPLICATION_CONFIG_PATH")?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: env("CONFIG_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it, "application.yaml") }
            ?: Path.of("configs", "application.yaml")
}

internal class RuntimeConfigLoader(
    private val applicationConfigLoader: ApplicationConfigLoader = ApplicationConfigLoader(),
    private val scoringConfigLoader: ScoringConfigLoader = ScoringConfigLoader(),
    private val ruleBasedScorerConfigLoader: RuleBasedScorerConfigLoader = RuleBasedScorerConfigLoader(),
    private val ruleConfigLoader: RuleConfigLoader = RuleConfigLoader(),
) {
    fun load(): RuntimeConfigBundle {
        val application = applicationConfigLoader.load()
        return load(application)
    }

    fun load(application: DemoRuntimeConfig): RuntimeConfigBundle {
        val scoring = loadScoring(application)
        val ruleBasedConfigs = loadRuleBasedConfigs(application.scoringConfigPath.parent ?: Path.of("."), scoring)
        val rules = loadRules(application.rulesDirectory, scoring.features.map { it.name }.toSet())
        return RuntimeConfigBundle(
            application = application,
            scoring = scoring,
            ruleBasedConfigsByPath = ruleBasedConfigs,
            initialRules = rules,
        )
    }

    private fun loadScoring(application: DemoRuntimeConfig): ScoringConfig {
        val path = application.scoringConfigPath
        if (!path.exists()) {
            throw RuntimeConfigException("scoring config not found: $path")
        }
        val scoring = try {
            scoringConfigLoader.load(path)
        } catch (error: RuntimeException) {
            throw RuntimeConfigException("invalid scoring config $path: ${error.message}", error)
        }
        return scoring
            .withSidecarOverride(application.scoringSidecarUrlOverride)
            .also(::validateScoringReferences)
    }

    private fun loadRuleBasedConfigs(
        scoringConfigDir: Path,
        scoring: ScoringConfig,
    ): Map<String, RuleBasedScorerConfig> =
        scoring.scorers
            .filter { it.type == ScorerType.RULE_BASED }
            .associate { scorer ->
                val configPath = scorer.configPath
                    ?: throw RuntimeConfigException("rule_based scorer ${scorer.name} requires config_path")
                val path = configPath.resolveFromWorkingDirectoryOr(scoringConfigDir)
                if (!path.exists()) {
                    throw RuntimeConfigException("rule_based scorer config not found for ${scorer.name}: $path")
                }
                configPath to try {
                    ruleBasedScorerConfigLoader.load(path)
                } catch (error: RuntimeException) {
                    throw RuntimeConfigException("invalid rule_based scorer config $path: ${error.message}", error)
                }
            }

    private fun loadRules(
        rulesDirectory: Path,
        scoringFeatureNames: Set<String>,
    ): List<RuleDefinition> {
        if (!rulesDirectory.exists()) {
            throw RuntimeConfigException("rules config directory not found: $rulesDirectory")
        }
        val ruleFiles = Files.list(rulesDirectory).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".yaml") || it.fileName.toString().endsWith(".yml") }
                .sorted()
                .toList()
        }
        if (ruleFiles.isEmpty()) {
            throw RuntimeConfigException("rules config directory has no YAML files: $rulesDirectory")
        }

        val validator = RuleValidator(knownFraudFeatureNames() + scoringFeatureNames)
        val rules = ruleFiles.flatMap { path ->
            val ruleSet = try {
                ruleConfigLoader.load(path)
            } catch (error: RuleConfigException) {
                throw RuntimeConfigException("invalid rule config $path: ${error.message}", error)
            } catch (error: RuntimeException) {
                throw RuntimeConfigException("invalid rule config $path: ${error.message}", error)
            }
            try {
                validator.validate(ruleSet).rules
            } catch (error: RuleValidationException) {
                throw RuntimeConfigException("invalid rule config $path: ${error.message}", error)
            }
        }
        val duplicateIds = rules.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (duplicateIds.isNotEmpty()) {
            throw RuntimeConfigException("rule ids must be unique across configured files: ${duplicateIds.joinToString()}")
        }
        return rules
    }
}

private fun validateScoringReferences(scoring: ScoringConfig) {
    val scorerNames = scoring.scorers.map { it.name }.toSet()
    scoring.features.forEach { binding ->
        if (binding.scorer !in scorerNames) {
            throw RuntimeConfigException("scoring feature ${binding.name} references unknown scorer: ${binding.scorer}")
        }
    }
    scoring.scorers.forEach { scorer ->
        when (scorer.type) {
            ScorerType.FAILOVER -> {
                requireKnownScorer(scorer.name, "primary", scorer.primary, scorerNames)
                requireKnownScorer(scorer.name, "fallback", scorer.fallback, scorerNames)
            }
            ScorerType.SHADOW -> {
                requireKnownScorer(scorer.name, "primary", scorer.primary, scorerNames)
                scorer.shadows.forEach { shadow ->
                    requireKnownScorer(scorer.name, "shadow", shadow, scorerNames)
                }
            }
            ScorerType.XGBOOST,
            ScorerType.RULE_BASED,
            -> Unit
        }
    }
}

private fun requireKnownScorer(
    scorerName: String,
    fieldName: String,
    referencedName: String?,
    scorerNames: Set<String>,
) {
    val name = referencedName ?: throw RuntimeConfigException("$scorerName requires $fieldName scorer")
    if (name !in scorerNames) {
        throw RuntimeConfigException("$scorerName references unknown $fieldName scorer: $name")
    }
}

internal data class RuntimeConfigBundle(
    val application: DemoRuntimeConfig,
    val scoring: ScoringConfig,
    val ruleBasedConfigsByPath: Map<String, RuleBasedScorerConfig>,
    val initialRules: List<RuleDefinition>,
)

internal class RuntimeConfigException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

private fun ScoringConfig.withSidecarOverride(endpoint: String?): ScoringConfig {
    if (endpoint.isNullOrBlank()) {
        return this
    }
    return copy(
        scorers = scorers.map { scorer ->
            if (scorer.type == ScorerType.XGBOOST) {
                scorer.copy(sidecarAddress = endpoint)
            } else {
                scorer
            }
        },
    )
}

private fun knownFraudFeatureNames(): Set<String> =
    FraudFeatureNames::class.java.fields
        .filter { it.type == String::class.java }
        .map { it.get(null) as String }
        .toSet()

private fun String.resolveAgainst(base: Path): Path {
    val path = Path.of(this)
    return if (path.isAbsolute) path else base.resolve(path).normalize()
}

private fun String.resolveFromWorkingDirectoryOr(base: Path): Path {
    val path = Path.of(this)
    if (path.isAbsolute || path.exists()) {
        return path
    }
    val sibling = base.parent?.resolve(path)?.normalize()
    if (sibling != null && sibling.exists()) {
        return sibling
    }
    return base.resolve(path).normalize()
}

private fun String.toUri(name: String): URI =
    try {
        URI.create(this)
    } catch (error: IllegalArgumentException) {
        throw RuntimeConfigException("$name must be a valid URI: $this", error)
    }

private fun String.toPort(name: String): Int {
    val port = toIntOrNull() ?: throw RuntimeConfigException("$name must be an integer port")
    if (port !in 1..65535) {
        throw RuntimeConfigException("$name must be between 1 and 65535")
    }
    return port
}

private fun String.toRoutePath(name: String): String {
    if (!startsWith("/")) {
        throw RuntimeConfigException("$name must start with /")
    }
    return this
}

@Suppress("UNCHECKED_CAST")
private fun Any?.asMap(path: String): Map<String, Any?> =
    (this as? Map<*, *>)
        ?.mapKeys { (key, _) ->
            key as? String ?: throw RuntimeConfigException("$path keys must be strings")
        }
        ?: throw RuntimeConfigException("$path must be an object")

private fun Map<String, Any?>.map(name: String): Map<String, Any?>? {
    val value = this[name] ?: return null
    return value.asMap(name)
}

private fun Map<String, Any?>.requiredMap(name: String): Map<String, Any?> =
    map(name) ?: throw RuntimeConfigException("$name is required")

private fun Map<String, Any?>.requiredString(name: String): String =
    string(name) ?: throw RuntimeConfigException("$name is required")

private fun Map<String, Any?>.string(name: String): String? {
    val value = this[name] ?: return null
    val text = value as? String ?: value.toString()
    if (text.isBlank()) {
        throw RuntimeConfigException("$name must not be blank")
    }
    return text
}
