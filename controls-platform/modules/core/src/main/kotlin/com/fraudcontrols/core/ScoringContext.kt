package com.fraudcontrols.core

data class ScoringContext(
    val event: TransactionEvent,
) {
    val eventId: EventId = event.eventId
}
