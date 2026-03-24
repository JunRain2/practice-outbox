package com.junrain.outbox.infra.event

import com.junrain.outbox.application.mock.MockService
import com.junrain.outbox.application.mock.command.ProcessMockEventCommand
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.LocalDateTime
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest
class EventProcessorTest {
    @Autowired
    lateinit var eventProcessor: EventProcessor

    @Autowired
    lateinit var eventRepository: EventRepository

    @MockitoSpyBean
    lateinit var mockService: MockService

    @AfterEach
    fun tearDown() {
        eventRepository.deleteAllInBatch()
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

    // --- 처리 ---

    @Test
    fun `이벤트 처리 성공 시 SUCCEEDED로 마킹된다`() {
        // given
        val event = createPendingEvent()

        // when
        eventProcessor.process(event.id)

        // then
        val updated = eventRepository.findById(event.id).get()
        assertThat(updated.status).isEqualTo(EventStatus.SUCCEEDED)
        assertThat(updated.processedAt).isNotNull()
        assertThat(updated.attemptCount).isEqualTo(0)
    }

    @Test
    fun `이벤트 처리 실패 시 PENDING을 유지하고 attemptCount가 증가하며 지수 백오프가 적용된다`() {
        // given
        val event = createPendingEvent()
        doThrow(RuntimeException("처리 실패")).whenever(mockService).processEvent(any())

        // when & then – 실패할 때마다 nextAttemptAt이 baseSeconds * 2^(attemptCount-1) 만큼 증가
        repeat(4) { i ->
            val before = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            eventProcessor.process(event.id)
            val updated = eventRepository.findById(event.id).get()

            assertThat(updated.status).isEqualTo(EventStatus.PENDING)
            assertThat(updated.attemptCount).isEqualTo(i + 1)
            assertThat(updated.processedAt).isNull()

            // 지수 백오프 검증: baseSeconds(60) * 2^i 초 후
            val expectedBackoffSeconds = 60L shl i // 60, 120, 240, 480
            assertThat(updated.nextAttemptAt)
                .isAfterOrEqualTo(before.plusSeconds(expectedBackoffSeconds))
                .isBeforeOrEqualTo(before.plusSeconds(expectedBackoffSeconds + 3))
        }
    }

    @Test
    fun `5회 이상 실패 시 FAILED로 마킹된다`() {
        // given
        val event = createPendingEvent()
        doThrow(RuntimeException("처리 실패")).whenever(mockService).processEvent(any())

        // when
        repeat(5) { eventProcessor.process(event.id) }

        // then
        val updated = eventRepository.findById(event.id).get()
        assertThat(updated.status).isEqualTo(EventStatus.FAILED)
        assertThat(updated.attemptCount).isEqualTo(5)
    }

    // --- 동시성 ---

    @Test
    fun `동일 이벤트에 대해 동시에 process를 호출해도 하나만 처리된다`() {
        // given
        val event = createPendingEvent()
        val threadCount = 10
        val barrier = CyclicBarrier(threadCount)
        val processedCount = AtomicInteger(0)
        val skippedCount = AtomicInteger(0)

        // when
        val threads = (1..threadCount).map {
            Thread {
                barrier.await(5, TimeUnit.SECONDS)
                val result = eventProcessor.process(event.id)
                if (result != null) processedCount.incrementAndGet()
                else skippedCount.incrementAndGet()
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }

        // then
        assertThat(processedCount.get()).isEqualTo(1)
        assertThat(skippedCount.get()).isEqualTo(threadCount - 1)
        verify(mockService, times(1)).processEvent(any<ProcessMockEventCommand>())
        assertThat(eventRepository.findById(event.id).get().status).isEqualTo(EventStatus.SUCCEEDED)
    }

    @Test
    fun `서로 다른 이벤트는 동시에 처리할 수 있다`() {
        // given
        val event1 = createPendingEvent("1")
        val event2 = createPendingEvent("2")
        val barrier = CyclicBarrier(2)
        val results = arrayOfNulls<Any>(2)

        // when
        val t1 = Thread {
            barrier.await(5, TimeUnit.SECONDS)
            results[0] = eventProcessor.process(event1.id)
        }
        val t2 = Thread {
            barrier.await(5, TimeUnit.SECONDS)
            results[1] = eventProcessor.process(event2.id)
        }
        t1.start(); t2.start()
        t1.join(10_000); t2.join(10_000)

        // then
        assertThat(results).allMatch { it != null }
        assertThat(eventRepository.findById(event1.id).get().status).isEqualTo(EventStatus.SUCCEEDED)
        assertThat(eventRepository.findById(event2.id).get().status).isEqualTo(EventStatus.SUCCEEDED)
    }
}
