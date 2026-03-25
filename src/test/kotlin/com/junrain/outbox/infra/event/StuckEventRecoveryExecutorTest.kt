package com.junrain.outbox.infra.event

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime

@SpringBootTest
class StuckEventRecoveryExecutorTest {
    @Autowired
    lateinit var eventRepository: EventRepository

    @Autowired
    lateinit var stuckEventRecoveryExecutor: StuckEventRecoveryExecutor

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @AfterEach
    fun tearDown() {
        eventRepository.deleteAllInBatch()
    }

    private fun createEvent(): Event =
        eventRepository.save(
            Event(
                aggregateType = AggregateType.MOCK,
                aggregateId = "1",
                eventType = EventType.MOCK_CREATED,
                payload = """{"mockId":1,"content":"test"}""",
            ),
        )

    private fun markAsProgressingWithAttemptedAt(
        eventId: Long,
        attemptedAt: LocalDateTime,
    ) {
        jdbcTemplate.update(
            "UPDATE event SET status = 'PROGRESSING', attempted_at = ? WHERE id = ?",
            attemptedAt,
            eventId,
        )
    }

    // --- recoverStuckEvents ---

    @Test
    fun `PROGRESSING 상태이고 attemptedAt이 threshold 이전이면 PENDING으로 복구된다`() {
        // given
        val event = createEvent()
        markAsProgressingWithAttemptedAt(event.id, LocalDateTime.now().minusMinutes(10))

        // when
        val recovered = eventRepository.recoverStuckEvents(LocalDateTime.now())

        // then
        assertThat(recovered).isEqualTo(1)
        assertThat(eventRepository.findById(event.id).get().status).isEqualTo(EventStatus.PENDING)
    }

    @Test
    fun `PROGRESSING 상태이고 attemptedAt이 threshold 이후이면 복구되지 않는다`() {
        // given
        val event = createEvent()
        markAsProgressingWithAttemptedAt(event.id, LocalDateTime.now())

        // when
        val recovered = eventRepository.recoverStuckEvents(LocalDateTime.now().minusMinutes(10))

        // then
        assertThat(recovered).isEqualTo(0)
        assertThat(eventRepository.findById(event.id).get().status).isEqualTo(EventStatus.PROGRESSING)
    }

    @Test
    fun `PENDING, SUCCEEDED, FAILED 상태 이벤트는 영향받지 않는다`() {
        // given
        val pending = createEvent()
        val succeeded = createEvent()
        val failed = createEvent()

        eventRepository.markAsProgress(succeeded.id)
        val successEvent = eventRepository.findById(succeeded.id).get()
        successEvent.attempt()
        successEvent.success()
        eventRepository.save(successEvent)

        eventRepository.markAsProgress(failed.id)
        val failEvent = eventRepository.findById(failed.id).get()
        failEvent.attempt()
        failEvent.fail(1, 60)
        eventRepository.save(failEvent)

        // when
        val recovered = eventRepository.recoverStuckEvents(LocalDateTime.now().plusMinutes(10))

        // then
        assertThat(recovered).isEqualTo(0)
        assertThat(eventRepository.findById(pending.id).get().status).isEqualTo(EventStatus.PENDING)
        assertThat(eventRepository.findById(succeeded.id).get().status).isEqualTo(EventStatus.SUCCEEDED)
        assertThat(eventRepository.findById(failed.id).get().status).isEqualTo(EventStatus.FAILED)
    }

    @Test
    fun `복구 대상이 여러 개일 때 모두 복구되고 행 수를 반환한다`() {
        // given
        val event1 = createEvent()
        val event2 = createEvent()
        val event3 = createEvent()
        val past = LocalDateTime.now().minusMinutes(10)
        markAsProgressingWithAttemptedAt(event1.id, past)
        markAsProgressingWithAttemptedAt(event2.id, past)
        markAsProgressingWithAttemptedAt(event3.id, past)

        // when
        val recovered = eventRepository.recoverStuckEvents(LocalDateTime.now())

        // then
        assertThat(recovered).isEqualTo(3)
    }

    // --- StuckEventRecoveryExecutor ---

    @Test
    fun `Executor가 recoveryTimeoutSeconds 기준으로 stuck 이벤트를 복구한다`() {
        // given
        val event = createEvent()
        markAsProgressingWithAttemptedAt(event.id, LocalDateTime.now().minusMinutes(10))

        // when
        stuckEventRecoveryExecutor.process()

        // then
        assertThat(eventRepository.findById(event.id).get().status).isEqualTo(EventStatus.PENDING)
    }
}
