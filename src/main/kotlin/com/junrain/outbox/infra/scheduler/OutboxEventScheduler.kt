package com.junrain.outbox.infra.scheduler

import com.junrain.outbox.domain.LockManager
import com.junrain.outbox.infra.config.EventSchedulerProperties
import com.junrain.outbox.infra.event.EventExecutor
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

@Component
class OutboxEventScheduler(
    private val lockManager: LockManager,
    private val eventExecutor: EventExecutor,
    private val schedulerProperties: EventSchedulerProperties,
) {
    companion object {
        const val OUTBOX_KEY = "outbox"
    }

    @Scheduled(fixedRateString = "\${outbox.scheduler.interval-seconds}s")
    fun process() =
        lockManager.executeWithLock(
            OUTBOX_KEY,
            leaseTime = Duration.ofSeconds(schedulerProperties.intervalSeconds),
        ) {
            eventExecutor.execute(
                intervalSeconds = schedulerProperties.intervalSeconds,
                now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS),
            )
        }
}
