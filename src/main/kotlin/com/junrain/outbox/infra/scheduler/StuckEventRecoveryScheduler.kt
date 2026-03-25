package com.junrain.outbox.infra.scheduler

import com.junrain.outbox.infra.event.StuckEventRecoveryExecutor
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@ConditionalOnProperty(name = ["outbox.lock-type"], havingValue = "db")
@Component
class StuckEventRecoveryScheduler(
    val processor: StuckEventRecoveryExecutor,
) {
    @Scheduled(fixedRateString = "\${outbox.db-lock.recovery-timeout-seconds}s")
    fun recoverStuckEvents() {
        processor.process()
    }
}
