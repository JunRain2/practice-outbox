package com.junrain.outbox.infra.config

import com.junrain.outbox.infra.event.EventType
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Outbox 이벤트의 재시도 정책을 정의합니다.
 * 이벤트 타입별 전략이 없으면 기본 전략을 사용합니다.
 */
@ConfigurationProperties(prefix = "outbox.retry")
data class EventRetryProperties(
    val strategies: Map<EventType, EventRetryStrategy> = emptyMap(),
    val default: EventRetryStrategy = EventRetryStrategy(),
) {
    fun getStrategy(eventType: EventType): EventRetryStrategy = strategies[eventType] ?: default
}

data class EventRetryStrategy(
    val maxCount: Int = 5,
    val baseSeconds: Long = 60,
)

/**
 * Outbox 이벤트의 스케줄러 정책을 정의합니다.
 */
@ConfigurationProperties(prefix = "outbox.scheduler")
data class EventSchedulerProperties(
    val intervalSeconds: Long = 60,
)

/**
 * DB Lock 전용 설정을 정의합니다.
 */
@ConfigurationProperties(prefix = "outbox.db-lock")
data class DbLockProperties(
    val recoveryTimeoutSeconds: Long = 30,
)
