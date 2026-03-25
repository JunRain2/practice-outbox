package com.junrain.outbox.infra.event

import com.junrain.outbox.application.mock.MockService
import com.junrain.outbox.application.mock.command.ProcessMockEventCommand
import com.junrain.outbox.domain.LockManager
import com.junrain.outbox.infra.config.EventRetryProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.reset
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

@SpringBootTest
class EventProcessorContractTest {
    @Autowired
    lateinit var eventRepository: EventRepository

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var lockManager: LockManager

    @Autowired
    lateinit var retryProperties: EventRetryProperties

    @MockitoSpyBean
    lateinit var mockService: MockService

    private val processors: List<EventProcessor> by lazy {
        listOf(
            DbLockEventProcessor(eventRepository, mockService, objectMapper, retryProperties),
            RedisLockEventProcessor(eventRepository, mockService, objectMapper, lockManager, retryProperties),
        )
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

    private fun cleanup() {
        eventRepository.deleteAllInBatch()
        reset(mockService)
    }

    private fun forEachProcessor(testBody: (EventProcessor) -> Unit): List<DynamicTest> =
        processors.map { processor ->
            dynamicTest(processor::class.simpleName!!) {
                try {
                    testBody(processor)
                } finally {
                    cleanup()
                }
            }
        }

    // --- 처리 ---

    @TestFactory
    fun `이벤트 처리 성공 시 SUCCEEDED로 마킹된다`() =
        forEachProcessor { processor ->
            val event = createPendingEvent()

            processor.process(event.id)

            val updated = eventRepository.findById(event.id).get()
            assertThat(updated.status).isEqualTo(EventStatus.SUCCEEDED)
            assertThat(updated.attemptedAt).isNotNull()
            assertThat(updated.attemptCount).isEqualTo(1)
        }

    @TestFactory
    fun `이벤트 처리 실패 시 PENDING을 유지하고 지수 백오프가 적용된다`() =
        forEachProcessor { processor ->
            val event = createPendingEvent()
            doThrow(RuntimeException("처리 실패")).whenever(mockService).processEvent(any())

            repeat(4) { i ->
                val before = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                processor.process(event.id)
                val updated = eventRepository.findById(event.id).get()

                assertThat(updated.status).isEqualTo(EventStatus.PENDING)
                assertThat(updated.attemptCount).isEqualTo(i + 1)
                assertThat(updated.attemptedAt).isNotNull()

                val expectedBackoffSeconds = 60L shl i
                assertThat(updated.nextAttemptAt)
                    .isAfterOrEqualTo(before.plusSeconds(expectedBackoffSeconds))
                    .isBeforeOrEqualTo(before.plusSeconds(expectedBackoffSeconds + 3))
            }
        }

    @TestFactory
    fun `5회 이상 실패 시 FAILED로 마킹된다`() =
        forEachProcessor { processor ->
            val event = createPendingEvent()
            doThrow(RuntimeException("처리 실패")).whenever(mockService).processEvent(any())

            repeat(5) { processor.process(event.id) }

            val updated = eventRepository.findById(event.id).get()
            assertThat(updated.status).isEqualTo(EventStatus.FAILED)
            assertThat(updated.attemptCount).isEqualTo(5)
        }

    // --- 동시성: 단일 스레드 보장 ---

    @TestFactory
    fun `동일 이벤트에 대해 여러 스레드가 동시에 호출해도 단 하나만 실제 처리한다`() =
        forEachProcessor { processor ->
            val event = createPendingEvent()
            val threadCount = 10
            val barrier = CyclicBarrier(threadCount)

            val threads =
                (1..threadCount).map {
                    Thread {
                        barrier.await(5, TimeUnit.SECONDS)
                        processor.process(event.id)
                    }
                }
            threads.forEach { it.start() }
            threads.forEach { it.join(10_000) }

            verify(mockService, times(1)).processEvent(any<ProcessMockEventCommand>())
            assertThat(eventRepository.findById(event.id).get().status).isEqualTo(EventStatus.SUCCEEDED)
        }

    @TestFactory
    fun `이벤트 처리 중에도 다른 스레드는 진입하지 못한다`() =
        forEachProcessor { processor ->
            val event = createPendingEvent()
            val processingStarted = CountDownLatch(1)
            val otherThreadsDone = CountDownLatch(1)

            doAnswer {
                processingStarted.countDown()
                otherThreadsDone.await(5, TimeUnit.SECONDS)
            }.whenever(mockService).processEvent(any())

            val t1 = Thread { processor.process(event.id) }
            t1.start()
            processingStarted.await(5, TimeUnit.SECONDS)

            val laterThreads =
                (1..5).map {
                    Thread { processor.process(event.id) }
                }
            laterThreads.forEach { it.start() }
            laterThreads.forEach { it.join(5_000) }

            otherThreadsDone.countDown()
            t1.join(5_000)

            verify(mockService, times(1)).processEvent(any<ProcessMockEventCommand>())
        }

    @TestFactory
    fun `서로 다른 이벤트는 동시에 처리할 수 있다`() =
        forEachProcessor { processor ->
            val event1 = createPendingEvent("1")
            val event2 = createPendingEvent("2")
            val barrier = CyclicBarrier(2)

            val t1 =
                Thread {
                    barrier.await(5, TimeUnit.SECONDS)
                    processor.process(event1.id)
                }
            val t2 =
                Thread {
                    barrier.await(5, TimeUnit.SECONDS)
                    processor.process(event2.id)
                }
            t1.start()
            t2.start()
            t1.join(10_000)
            t2.join(10_000)

            assertThat(eventRepository.findById(event1.id).get().status).isEqualTo(EventStatus.SUCCEEDED)
            assertThat(eventRepository.findById(event2.id).get().status).isEqualTo(EventStatus.SUCCEEDED)
        }
}
