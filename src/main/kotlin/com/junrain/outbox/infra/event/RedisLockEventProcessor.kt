package com.junrain.outbox.infra.event

import com.junrain.outbox.application.mock.MockService
import com.junrain.outbox.application.mock.command.ProcessMockEventCommand
import com.junrain.outbox.domain.LockManager
import com.junrain.outbox.infra.config.EventRetryProperties
import com.junrain.outbox.infra.event.mock.MockCreatedSnapshot
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class RedisLockEventProcessor(
    private val eventRepository: EventRepository,
    private val mockService: MockService,
    private val objectMapper: ObjectMapper,
    private val lockManager: LockManager,
    private val retryProperties: EventRetryProperties,
) : EventProcessor {
    companion object {
        private const val EVENT_KEY_PREFIX = "event_id:"

        fun eventKey(eventId: Long) = "$EVENT_KEY_PREFIX$eventId"
    }

    /**
     * 분산 락을 획득한 뒤 이벤트를 실행하고 결과에 따라 성공/실패 처리합니다.
     * 락 획득에 실패하면 스킵합니다.
     */
    override fun process(eventId: Long) {
        lockManager.executeWithLock(eventKey(eventId)) {
            val event =
                eventRepository.findByIdAndStatus(eventId, EventStatus.PENDING)
                    ?: return@executeWithLock

            // TODO OCP를 충족하도록 전환
            // EventType이 늘어날 때마다 when 분기가 늘어남
            try {
                event.attempt()
                when (event.eventType) {
                    EventType.MOCK_CREATED -> {
                        val mockBody =
                            objectMapper.readValue(event.payload, MockCreatedSnapshot::class.java)
                        mockService.processEvent(
                            ProcessMockEventCommand(
                                mockId = mockBody.mockId,
                                content = mockBody.content,
                            ),
                        )
                    }
                }
                event.success()
            } catch (e: RuntimeException) {
                val strategy = retryProperties.getStrategy(event.eventType)
                event.fail(strategy.maxCount, strategy.baseSeconds)
            } finally {
                eventRepository.save(event)
            }
        }
    }
}
