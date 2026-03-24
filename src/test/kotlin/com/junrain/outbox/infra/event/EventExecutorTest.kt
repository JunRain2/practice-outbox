package com.junrain.outbox.infra.event

import com.junrain.outbox.application.mock.MockService
import com.junrain.outbox.application.mock.command.CreateMockCommand
import com.junrain.outbox.application.mock.command.ProcessMockEventCommand
import com.junrain.outbox.domain.mock.MockRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doCallRealMethod
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.never
import org.mockito.kotlin.timeout
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

@SpringBootTest
class EventExecutorTest {
    @Autowired
    lateinit var eventExecutor: EventExecutor

    @Autowired
    lateinit var eventRepository: EventRepository

    @Autowired
    lateinit var mockRepository: MockRepository

    @MockitoSpyBean
    lateinit var mockService: MockService

    @AfterEach
    fun tearDown() {
        eventRepository.deleteAllInBatch()
        mockRepository.deleteAllInBatch()
    }

    private fun createPendingEvent(aggregateId: String = "1"): Event =
        eventRepository.save(
            Event(
                aggregateType = AggregateType.MOCK,
                aggregateId = aggregateId,
                eventType = EventType.MOCK_CREATED,
                payload = """{"mockId":$aggregateId,"content":"test"}""",
            ),
        )

    private fun executeAt(now: LocalDateTime, intervalSeconds: Long = 0) {
        eventExecutor.execute(intervalSeconds = intervalSeconds, now = now)
    }

    // --- 조회 조건 ---

    @Test
    fun `pending 이벤트를 조회하여 처리한다`() {
        // given
        val event1 = createPendingEvent("1")
        val event2 = createPendingEvent("2")

        // when
        val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(60)
        executeAt(now)

        // then
        assertThat(eventRepository.findById(event1.id).get().status).isEqualTo(EventStatus.SUCCEEDED)
        assertThat(eventRepository.findById(event2.id).get().status).isEqualTo(EventStatus.SUCCEEDED)
    }

    @Test
    fun `PENDING 상태가 아닌 이벤트는 조회하지 않는다`() {
        // given
        val event = createPendingEvent()
        val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(60)
        executeAt(now)
        assertThat(eventRepository.findById(event.id).get().status).isEqualTo(EventStatus.SUCCEEDED)

        // when
        clearInvocations(mockService)
        executeAt(now)

        // then
        verify(mockService, never()).processEvent(any<ProcessMockEventCommand>())
    }

    @Test
    fun `intervalSeconds 이전에 생성된 이벤트만 조회한다`() {
        // given
        createPendingEvent()
        val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)

        // when - 이벤트 생성 직후 now로 실행하면 intervalSeconds=60 조건에 의해 조회되지 않는다
        executeAt(now, intervalSeconds = 60)

        // then
        verify(mockService, never()).processEvent(any())

        // when - 120초 후 시점으로 실행하면 조회된다
        val laterNow = now.plusSeconds(120)
        executeAt(laterNow, intervalSeconds = 60)

        // then
        verify(mockService, times(1)).processEvent(any<ProcessMockEventCommand>())
    }

    @Test
    fun `처리 대상 이벤트가 없으면 processor를 호출하지 않는다`() {
        // given - 이벤트 없음

        // when
        val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
        executeAt(now, intervalSeconds = 60)

        // then
        verify(mockService, never()).processEvent(any())
    }

    // --- 복구 ---

    @Test
    fun `리스너가 이벤트 처리에 실패해도 Executor가 복구한다`() {
        // given
        doThrow(RuntimeException("리스너 처리 실패"))
            .doCallRealMethod()
            .whenever(mockService)
            .processEvent(any())

        mockService.createMock(CreateMockCommand(content = "recovery-test"))
        verify(mockService, timeout(3000)).processEvent(any())

        val event = eventRepository.findAll().first()
        assertThat(event.status).isEqualTo(EventStatus.PENDING)
        assertThat(event.attemptCount).isEqualTo(1)

        // when
        val futureNow = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(300)
        executeAt(futureNow)

        // then
        val recovered = eventRepository.findById(event.id).get()
        assertThat(recovered.status).isEqualTo(EventStatus.SUCCEEDED)
    }
}
