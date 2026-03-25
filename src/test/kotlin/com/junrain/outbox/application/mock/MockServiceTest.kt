package com.junrain.outbox.application.mock

import com.junrain.outbox.application.mock.command.CreateMockCommand
import com.junrain.outbox.domain.mock.MockEventPublisher
import com.junrain.outbox.domain.mock.MockRepository
import com.junrain.outbox.infra.event.AggregateType
import com.junrain.outbox.infra.event.EventProcessor
import com.junrain.outbox.infra.event.EventRepository
import com.junrain.outbox.infra.event.EventStatus
import com.junrain.outbox.infra.event.EventType
import com.junrain.outbox.infra.event.mock.MockCreatedSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import tools.jackson.databind.ObjectMapper

@SpringBootTest
class MockServiceTest {
    @Autowired
    lateinit var mockService: MockService

    @MockitoBean
    lateinit var eventProcessor: EventProcessor

    @MockitoSpyBean
    lateinit var mockEventPublisher: MockEventPublisher

    @Autowired
    lateinit var eventRepository: EventRepository

    @Autowired
    lateinit var mockRepository: MockRepository

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @AfterEach
    fun tearDown() {
        eventRepository.deleteAllInBatch()
        mockRepository.deleteAllInBatch()
    }

    @Test
    fun `Mock 생성 시 Event가 같은 트랜잭션에서 저장된다`() {
        // given
        val command = CreateMockCommand(content = "test-content")

        // when
        mockService.createMock(command)

        // then
        val mocks = mockRepository.findAll()
        assertThat(mocks).hasSize(1)
        val mock = mocks[0]

        val events = eventRepository.findAll()
        assertThat(events).hasSize(1)
        val event = events[0]

        assertThat(event.aggregateType).isEqualTo(AggregateType.MOCK)
        assertThat(event.aggregateId).isEqualTo(mock.id.toString())
        assertThat(event.eventType).isEqualTo(EventType.MOCK_CREATED)
        assertThat(event.status).isEqualTo(EventStatus.PENDING)
        assertThat(event.attemptCount).isEqualTo(0)
        assertThat(event.attemptedAt).isNull()
        assertThat(event.createdAt).isNotNull()

        val snapshot = objectMapper.readValue(event.payload, MockCreatedSnapshot::class.java)
        assertThat(snapshot.mockId).isEqualTo(mock.id)
        assertThat(snapshot.content).isEqualTo("test-content")
    }

    @Test
    fun `이벤트 발행 중 예외가 발생하면 Mock 저장도 함께 롤백된다`() {
        // given
        val command = CreateMockCommand(content = "rollback-test")
        doThrow(RuntimeException("발행 실패")).whenever(mockEventPublisher).publishCreatedMock(any())

        // when
        assertThrows<RuntimeException> { mockService.createMock(command) }

        // then - Mock과 Event 모두 롤백되어 존재하지 않아야 한다
        assertThat(mockRepository.findAll()).isEmpty()
        assertThat(eventRepository.findAll()).isEmpty()
    }

    @Test
    fun `트랜잭션 커밋 이후 비동기로 이벤트 리스너가 동작한다`() {
        // given
        val command = CreateMockCommand(content = "async-test")

        // when
        mockService.createMock(command)

        // then - @Async + @TransactionalEventListener(AFTER_COMMIT) 이므로 timeout으로 대기
        // EventProcessor.process()가 호출되면 리스너가 동작한 것
        verify(eventProcessor, timeout(3000)).process(any())
    }
}
