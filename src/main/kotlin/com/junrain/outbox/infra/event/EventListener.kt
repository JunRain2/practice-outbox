package com.junrain.outbox.infra.event

import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class EventListener(
    private val worker: EventProcessor,
) {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    fun onAfterCommit(event: PublishedEvent) {
        worker.process(event.eventId)
    }
}

data class PublishedEvent(
    val eventId: Long,
)
