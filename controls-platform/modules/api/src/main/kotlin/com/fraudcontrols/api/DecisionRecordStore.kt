package com.fraudcontrols.api

import com.fraudcontrols.core.EventId
import com.fraudcontrols.decisioning.DecisionAuditSink
import com.fraudcontrols.decisioning.DecisionRecord
import com.fraudcontrols.decisioning.DecisionRecordReader
import com.fraudcontrols.decisioning.contracts.DecisionAuditRowContract
import com.fraudcontrols.decisioning.contracts.toDecisionAuditRowContract
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryDecisionRecordStore(
    initialRecords: Iterable<DecisionRecord> = emptyList(),
) : DecisionAuditSink,
    DecisionRecordReader {
    private val mutex = Mutex()
    private val records = linkedMapOf<EventId, DecisionRecord>()

    init {
        for (record in initialRecords) {
            records[record.decision.eventId] = record
        }
    }

    override suspend fun record(record: DecisionRecord) {
        mutex.withLock {
            records[record.decision.eventId] = record
        }
    }

    override suspend fun find(eventId: EventId): DecisionAuditRowContract? = mutex.withLock {
        records[eventId]?.toDecisionAuditRowContract()
    }
}
