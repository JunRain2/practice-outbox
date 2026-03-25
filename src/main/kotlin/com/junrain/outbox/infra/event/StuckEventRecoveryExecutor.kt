package com.junrain.outbox.infra.event

import com.junrain.outbox.infra.config.EventSchedulerProperties
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class StuckEventRecoveryExecutor(
    private val eventRepository: EventRepository,
    private val schedulerProperties: EventSchedulerProperties,
) {
    /**
     * 처리 중 비정상 종료되어 PROGRESSING 상태로 남은 이벤트를 대기 상태로 복구합니다.
     */
    fun process() {
        val threshold = LocalDateTime.now().minusSeconds(schedulerProperties.recoveryTimeoutSeconds)
        eventRepository.recoverStuckEvents(threshold)
    }
}
