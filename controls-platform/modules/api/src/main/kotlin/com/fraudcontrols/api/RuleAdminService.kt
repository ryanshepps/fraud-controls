package com.fraudcontrols.api

import com.fraudcontrols.rules.RuleDefinition
import com.fraudcontrols.rules.RuleMode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

class RuleAdminService(
    initialRules: Iterable<RuleDefinition> = emptyList(),
    private val auditPublisher: RuleChangeAuditPublisher = NoopRuleChangeAuditPublisher,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mutex = Mutex()
    private val currentRules = linkedMapOf<String, RuleDefinition>()
    private val ruleHistory = linkedMapOf<String, MutableList<RuleDefinition>>()

    init {
        for (rule in initialRules) {
            currentRules[rule.id] = rule
            ruleHistory.getOrPut(rule.id) { mutableListOf() }.add(rule)
        }
    }

    suspend fun list(): List<RuleDefinition> = mutex.withLock {
        currentRules.values.sortedWith(compareByDescending<RuleDefinition> { it.priority }.thenBy { it.id })
    }

    suspend fun history(ruleId: String): List<RuleDefinition> = mutex.withLock {
        ruleHistory[ruleId]?.toList() ?: throw RuleAdminException.NotFound("rule not found: $ruleId")
    }

    suspend fun create(
        rule: RuleDefinition,
        actor: String,
    ): RuleDefinition {
        val (created, event) =
            mutex.withLock {
                if (currentRules.containsKey(rule.id)) {
                    throw RuleAdminException.Conflict("rule already exists: ${rule.id}")
                }
                val created = rule.copy(version = 1)
                currentRules[created.id] = created
                ruleHistory.getOrPut(created.id) { mutableListOf() }.add(created)
                created to
                    RuleChangeEvent(
                        ruleId = created.id,
                        ruleVersion = created.version,
                        changeType = RuleChangeType.CREATE,
                        actor = actor,
                        occurredAt = Instant.now(clock),
                        diff = diff(null, created),
                    )
            }
        auditPublisher.publish(event)
        return created
    }

    suspend fun update(
        ruleId: String,
        replacement: RuleDefinition,
        actor: String,
    ): RuleDefinition = mutateExisting(
        ruleId = ruleId,
        actor = actor,
        changeType = RuleChangeType.UPDATE,
    ) { previous ->
        replacement.copy(
            id = ruleId,
            version = previous.version + 1,
        )
    }

    suspend fun promote(
        ruleId: String,
        confirmed: Boolean,
        actor: String,
    ): RuleDefinition {
        if (!confirmed) {
            throw RuleAdminException.BadRequest("promotion requires confirmation")
        }
        return mutateExisting(
            ruleId = ruleId,
            actor = actor,
            changeType = RuleChangeType.PROMOTE,
        ) { previous ->
            if (previous.effectiveMode != RuleMode.SHADOW) {
                throw RuleAdminException.BadRequest("only shadow rules can be promoted")
            }
            previous.copy(
                version = previous.version + 1,
                enabled = true,
                mode = RuleMode.ENFORCE,
            )
        }
    }

    suspend fun disable(
        ruleId: String,
        actor: String,
    ): RuleDefinition = mutateExisting(
        ruleId = ruleId,
        actor = actor,
        changeType = RuleChangeType.DISABLE,
    ) { previous ->
        previous.copy(
            version = previous.version + 1,
            enabled = false,
            mode = RuleMode.DISABLED,
        )
    }

    private suspend fun mutateExisting(
        ruleId: String,
        actor: String,
        changeType: RuleChangeType,
        nextRule: (RuleDefinition) -> RuleDefinition,
    ): RuleDefinition {
        val (updated, event) =
            mutex.withLock {
                val previousRule = currentRules[ruleId] ?: throw RuleAdminException.NotFound("rule not found: $ruleId")
                val updated = nextRule(previousRule)
                currentRules[ruleId] = updated
                ruleHistory.getOrPut(ruleId) { mutableListOf() }.add(updated)
                updated to
                    RuleChangeEvent(
                        ruleId = ruleId,
                        ruleVersion = updated.version,
                        changeType = changeType,
                        actor = actor,
                        occurredAt = Instant.now(clock),
                        diff = diff(previousRule, updated),
                    )
            }
        auditPublisher.publish(event)
        return updated
    }
}

interface RuleChangeAuditPublisher {
    suspend fun publish(event: RuleChangeEvent)
}

object NoopRuleChangeAuditPublisher : RuleChangeAuditPublisher {
    override suspend fun publish(event: RuleChangeEvent) = Unit
}

class InMemoryRuleChangeAuditPublisher : RuleChangeAuditPublisher {
    private val mutex = Mutex()
    private val publishedEvents = mutableListOf<RuleChangeEvent>()

    override suspend fun publish(event: RuleChangeEvent) {
        mutex.withLock {
            publishedEvents += event
        }
    }

    suspend fun events(): List<RuleChangeEvent> = mutex.withLock {
        publishedEvents.toList()
    }
}

class KafkaRuleChangeAuditPublisher(
    private val producer: Producer<String, String>,
    private val topic: String = "rule_changes",
    private val sendTimeout: Duration = Duration.ofSeconds(5),
) : RuleChangeAuditPublisher {
    override suspend fun publish(event: RuleChangeEvent) {
        producer
            .send(
                ProducerRecord(topic, event.ruleId, event.toJsonObject().toString()),
            ).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS)
    }
}

data class RuleChangeEvent(
    val ruleId: String,
    val ruleVersion: Int,
    val changeType: RuleChangeType,
    val actor: String,
    val occurredAt: Instant,
    val diff: Map<String, String>,
) {
    init {
        require(ruleId.isNotBlank()) { "rule id must not be blank" }
        require(ruleVersion >= 0) { "rule version cannot be negative" }
        require(actor.isNotBlank()) { "actor must not be blank" }
    }
}

enum class RuleChangeType {
    CREATE,
    UPDATE,
    PROMOTE,
    DISABLE,
}

sealed class RuleAdminException(
    message: String,
) : IllegalArgumentException(message) {
    class BadRequest(
        message: String,
    ) : RuleAdminException(message)

    class Conflict(
        message: String,
    ) : RuleAdminException(message)

    class NotFound(
        message: String,
    ) : RuleAdminException(message)
}

private fun diff(
    previous: RuleDefinition?,
    next: RuleDefinition,
): Map<String, String> {
    if (previous == null) {
        return mapOf("created" to next.id)
    }

    return buildMap {
        addIfChanged("enabled", previous.enabled, next.enabled)
        addIfChanged("mode", previous.mode, next.mode)
        addIfChanged("priority", previous.priority, next.priority)
        addIfChanged("description", previous.description, next.description)
        addIfChanged("condition", previous.condition, next.condition)
        addIfChanged("action", previous.action, next.action)
    }
}

private fun MutableMap<String, String>.addIfChanged(
    name: String,
    previous: Any?,
    next: Any?,
) {
    if (previous != next) {
        this[name] = "${previous ?: "<none>"}->$next"
    }
}
