package com.junrain.outbox.infra.event

import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

@Component
class EventExecutor(
    private val eventRepository: EventRepository,
    private val eventWorker: EventProcessor,
) {
    /**
     * 처리 대기 중인 이벤트를 조회하여 순차적으로 처리합니다.
     * [intervalSeconds] 이전에 생성된 이벤트만 대상에 포함됩니다.
     */
    fun execute(
        intervalSeconds: Long,
        now: LocalDateTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS),
    ) {
        val eventIds =
            eventRepository
                .findAllPendingEventsToProcess(
                    status = EventStatus.PENDING,
                    createdBefore = now.minusSeconds(intervalSeconds),
                    now = now,
                ).map { it.id }

        // TODO 코루틴 기반 비동기 처리로 전환
        // 현재 순차 처리라 느린 이벤트가 전체를 병목시키고, 대량 적재 시 메모리 압박이 발생할 수 있음
        for (eventId in eventIds) {
            eventWorker.process(eventId)
        }
    }
}
