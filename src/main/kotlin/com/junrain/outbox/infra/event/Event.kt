package com.junrain.outbox.infra.event

import com.junrain.outbox.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import java.time.LocalDateTime

@Entity
class Event(
    @Enumerated(EnumType.STRING)
    val aggregateType: AggregateType, // 에그리거트 타입
    val aggregateId: String? = null, // 에그리거트 id
    @Enumerated(EnumType.STRING)
    val eventType: EventType, // 이벤트 타입
    @Column(columnDefinition = "TEXT")
    val payload: String, // 본문 (이벤트를 실행시킬 때 필요한 데이터)
) : BaseEntity() {
    @Enumerated(EnumType.STRING)
    var status: EventStatus = EventStatus.PENDING
        private set
    var attemptCount = 0 // 최대 전송 회수를 위한 정수
        private set
    var nextAttemptAt: LocalDateTime? = null // 백오프 지수를 위한 다음 전송 시간
        private set
    var processedAt: LocalDateTime? = null // 전송된 시각
        private set

    /**
     * 이벤트 상태가 [PENDING]일 때만 가능합니다.
     */
    fun canProcess(): Boolean = this.status == EventStatus.PENDING

    /**
     * 이벤트를 성공 처리합니다.
     * 처리 시각이 현재 시각으로 기록됩니다.
     */
    fun success() {
        processedAt = LocalDateTime.now()
        this.status = EventStatus.SUCCEEDED
    }

    /**
     * 이벤트를 실패 처리합니다.
     * 재시도 횟수가 [maxCount] 미만이면 지수 백오프로 다음 시도 시각을 설정하고,
     * 도달하면 FAILED로 확정합니다.
     */
    fun fail(
        maxCount: Int,
        baseSeconds: Long,
    ) {
        attemptCount++
        if (attemptCount >= maxCount) {
            this.status = EventStatus.FAILED
            return
        }
        nextAttemptAt =
            LocalDateTime
                .now()
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                .plusSeconds(baseSeconds shl (attemptCount - 1))
    }
}

enum class AggregateType {
    MOCK,
}

enum class EventType {
    MOCK_CREATED,
}

enum class EventStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
}
