package com.junrain.outbox.infra.scheduler

import com.junrain.outbox.infra.event.StuckEventRecoveryExecutor
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class StuckEventRecoveryScheduler(
    val processor: StuckEventRecoveryExecutor,
) {
    @Scheduled(fixedRateString = "\${outbox.scheduler.recovery-timeout-seconds}s")
    fun recoverStuckEvents() {
        processor.process()
    }
}
