package com.junrain.outbox.infra.event

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface EventRepository : JpaRepository<Event, Long> {
    /**
     * 생성된 지 [createdBefore]이 지난 대기 중인 이벤트 또는
     * 재시도 시각이 도래한 이벤트를 반환합니다.
     * 최근 생성된 이벤트는 아직 정상 처리 중일 수 있어 제외합니다.
     */
    @Query(
        """
        SELECT e FROM Event e
        WHERE e.status = :status
          AND (
              (e.nextAttemptAt IS NULL AND e.createdAt < :createdBefore)
              OR e.nextAttemptAt < :now
          )
        ORDER BY e.nextAttemptAt ASC
    """,
    )
    fun findAllPendingEventsToProcess(
        @Param("status") status: EventStatus,
        @Param("createdBefore") createdBefore: LocalDateTime,
        @Param("now") now: LocalDateTime,
    ): List<Event>
}
