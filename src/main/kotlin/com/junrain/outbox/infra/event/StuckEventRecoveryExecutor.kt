package com.junrain.outbox.infra.event

import com.junrain.outbox.infra.config.DbLockProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@ConditionalOnProperty(name = ["outbox.lock-type"], havingValue = "db")
@Component
class StuckEventRecoveryExecutor(
    private val eventRepository: EventRepository,
    private val dbLockProperties: DbLockProperties,
) {
    /**
     * 처리 중 비정상 종료되어 PROGRESSING 상태로 남은 이벤트를 대기 상태로 복구합니다.
     */
    fun process() {
        val threshold = LocalDateTime.now().minusSeconds(dbLockProperties.recoveryTimeoutSeconds)
        eventRepository.recoverStuckEvents(threshold)
    }
}
