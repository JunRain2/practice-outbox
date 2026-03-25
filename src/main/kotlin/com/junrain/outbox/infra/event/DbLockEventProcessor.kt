package com.junrain.outbox.infra.event

import com.junrain.outbox.application.mock.MockService
import com.junrain.outbox.application.mock.command.ProcessMockEventCommand
import com.junrain.outbox.infra.config.EventRetryProperties
import com.junrain.outbox.infra.event.mock.MockCreatedSnapshot
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Primary
@Component
class DbLockEventProcessor(
    private val eventRepository: EventRepository,
    private val mockService: MockService,
    private val objectMapper: ObjectMapper,
    private val retryProperties: EventRetryProperties,
) : EventProcessor {
    /**
     * 이벤트를 원자적으로 선점한 뒤 실행하고 결과에 따라 성공/실패 처리합니다.
     * 선점에 실패하면 스킵됩니다.
     */
    override fun process(eventId: Long) {
        // 동일 이벤트의 중복 처리를 방지하기 위해 상태를 먼저 변경
        if (eventRepository.markAsProgress(eventId) == 0) return

        val event = eventRepository.findByIdAndStatus(eventId, EventStatus.PROGRESSING) ?: return

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
