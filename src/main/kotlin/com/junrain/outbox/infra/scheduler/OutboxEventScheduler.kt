package com.junrain.outbox.infra.scheduler

import com.junrain.outbox.infra.config.EventSchedulerProperties
import com.junrain.outbox.infra.event.EventExecutor
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

@Component
class OutboxEventScheduler(
    private val eventExecutor: EventExecutor,
    private val schedulerProperties: EventSchedulerProperties,
) {
    @Scheduled(fixedRateString = "\${outbox.scheduler.interval-seconds}s")
    fun process() {
        eventExecutor.execute(
            intervalSeconds = schedulerProperties.intervalSeconds,
            now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS),
        )
    }
}
