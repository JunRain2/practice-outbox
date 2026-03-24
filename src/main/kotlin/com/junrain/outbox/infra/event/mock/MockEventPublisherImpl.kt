package com.junrain.outbox.infra.event.mock

import com.junrain.outbox.domain.mock.Mock
import com.junrain.outbox.domain.mock.MockEventPublisher
import com.junrain.outbox.infra.event.AggregateType
import com.junrain.outbox.infra.event.Event
import com.junrain.outbox.infra.event.EventRepository
import com.junrain.outbox.infra.event.EventType
import com.junrain.outbox.infra.event.PublishedEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class MockEventPublisherImpl(
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val objectMapper: ObjectMapper,
    private val eventRepository: EventRepository,
) : MockEventPublisher {
    override fun publishCreatedMock(mock: Mock) {
        val snapshot =
            MockCreatedSnapshot(
                mockId = mock.id,
                content = mock.content,
            )
        val event =
            eventRepository.save(
                Event(
                    aggregateType = AggregateType.MOCK,
                    aggregateId = mock.id.toString(),
                    eventType = EventType.MOCK_CREATED,
                    payload = objectMapper.writeValueAsString(snapshot),
                ),
            )

        applicationEventPublisher.publishEvent(PublishedEvent(event.id))
    }
}

data class MockCreatedSnapshot(
    val mockId: Long,
    val content: String,
)
