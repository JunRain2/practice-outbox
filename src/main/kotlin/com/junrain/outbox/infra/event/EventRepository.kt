package com.junrain.outbox.infra.event

import jakarta.transaction.Transactional
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
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

    fun findByIdAndStatus(
        id: Long,
        status: EventStatus,
    ): Event?

    /**
     * 대기중인 이벤트를 진행중으로 변경하고 갱신 여부를 반환합니다. (0 or 1)
     * 갱신에 실패한 경우 0을 반환합니다.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(
        """
        UPDATE Event e SET e.status='PROGRESSING'
          WHERE e.id = :id AND e.status = 'PENDING'
          """,
    )
    fun markAsProgress(id: Long): Int

    /**
     * PROGRESSING 상태이고 attemptedAt이 [threshold] 이전인 stuck 이벤트를 PENDING으로 복구합니다.
     * 복구된 행 수를 반환합니다.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(
        """
        UPDATE Event e SET e.status = 'PENDING'
        WHERE e.status = 'PROGRESSING'
          AND e.attemptedAt <= :threshold
        """,
    )
    fun recoverStuckEvents(@Param("threshold") threshold: LocalDateTime): Int
}
