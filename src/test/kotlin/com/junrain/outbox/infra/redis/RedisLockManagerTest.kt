package com.junrain.outbox.infra.redis

import com.junrain.outbox.domain.LockManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Duration
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest
class RedisLockManagerTest {
    @Autowired
    lateinit var lockManager: LockManager

    // --- 기본 동작 ---

    @Test
    fun `락 획득 후 action 실행 결과를 반환한다`() {
        val result = lockManager.executeWithLock("basic-test") { "hello" }

        assertThat(result).isEqualTo("hello")
    }

    @Test
    fun `락 해제 후 동일 키에 대해 재획득이 가능하다`() {
        val key = "reacquire-test"

        val firstResult =
            lockManager.executeWithLock(key, leaseTime = Duration.ofSeconds(1)) { "first" }
        val secondResult =
            lockManager.executeWithLock(key, leaseTime = Duration.ofSeconds(1)) { "second" }

        assertThat(firstResult).isEqualTo("first")
        assertThat(secondResult).isEqualTo("second")
    }

    // --- waitTime ---

    @Test
    fun `waitTime이 0이면 락 점유 중 null을 반환한다`() =
        runBlocking {
            val key = "wait-zero-test"
            val lockHeld = CompletableDeferred<Unit>()
            val canRelease = CompletableDeferred<Unit>()

            // 스레드 A: 락을 잡고 대기
            val job =
                launch(Dispatchers.IO) {
                    lockManager.executeWithLock(key, leaseTime = Duration.ofSeconds(5)) {
                        lockHeld.complete(Unit)
                        runBlocking { canRelease.await() }
                    }
                }
            lockHeld.await()

            // 스레드 B: waitTime=0이므로 즉시 null 반환
            val result =
                withContext(Dispatchers.IO) {
                    lockManager.executeWithLock(key) { "should-not-execute" }
                }
            assertThat(result).isNull()

            canRelease.complete(Unit)
            job.join()
        }

    @Test
    fun `waitTime 내에 락이 해제되면 획득에 성공한다`() =
        runBlocking {
            val key = "wait-success-test"
            val lockHeld = CompletableDeferred<Unit>()

            // 스레드 A: 락을 잡고 500ms 후 해제
            val job =
                launch(Dispatchers.IO) {
                    lockManager.executeWithLock(key, leaseTime = Duration.ofSeconds(5)) {
                        lockHeld.complete(Unit)
                        Thread.sleep(500)
                    }
                }
            lockHeld.await()

            // 스레드 B: 3초까지 대기 → A가 500ms 후 풀어주므로 성공
            val result =
                withContext(Dispatchers.IO) {
                    lockManager.executeWithLock(
                        key,
                        waitTime = Duration.ofSeconds(3),
                        leaseTime = Duration.ofSeconds(5),
                    ) { "acquired" }
                }
            assertThat(result).isEqualTo("acquired")

            job.join()
        }

    @Test
    fun `waitTime 만큼 기다려도 점유에 실패하면 예외를 던진다`() =
        runBlocking {
            val key = "wait-fail-test"
            val lockHeld = CompletableDeferred<Unit>()
            val canRelease = CompletableDeferred<Unit>()

            // 스레드 A: 락을 잡고 계속 유지
            val job =
                launch(Dispatchers.IO) {
                    lockManager.executeWithLock(key, leaseTime = Duration.ofSeconds(10)) {
                        lockHeld.complete(Unit)
                        runBlocking { canRelease.await() }
                    }
                }
            lockHeld.await()

            // 스레드 B: 100ms만 대기 → 실패 → 예외
            assertThatThrownBy {
                lockManager.executeWithLock(
                    key,
                    waitTime = Duration.ofMillis(100),
                    leaseTime = Duration.ofSeconds(5),
                ) { "should-not-execute" }
            }.hasMessageContaining("락 획득에 실패했습니다")

            canRelease.complete(Unit)
            job.join()
        }

    // --- leaseTime ---

    @Test
    fun `leaseTime 경과 후 락이 자동 해제된다`(): Unit =
        runBlocking {
            val key = "lease-expire-test"
            val lockHeld = CompletableDeferred<Unit>()

            // 스레드 A: leaseTime 1초로 락 획득, action에서 오래 대기
            launch(Dispatchers.IO) {
                lockManager.executeWithLock(key, leaseTime = Duration.ofSeconds(1)) {
                    lockHeld.complete(Unit)
                    Thread.sleep(3000) // leaseTime보다 오래 대기
                }
            }
            lockHeld.await()

            // 1.5초 후 락이 자동 만료됐으므로 다른 스레드가 획득 가능
            Thread.sleep(1500)

            val result =
                withContext(Dispatchers.IO) {
                    lockManager.executeWithLock(
                        key,
                        leaseTime = Duration.ofSeconds(5),
                    ) { "acquired-after-expire" }
                }
            assertThat(result).isEqualTo("acquired-after-expire")
        }

    // --- 동시성 ---

    @Test
    fun `동시에 하나의 스레드만 락을 획득한다`(): Unit =
        runBlocking {
            val threadCount = 10
            val barrier = CyclicBarrier(threadCount)
            val lockAcquiredCount = AtomicInteger()
            val lockSkippedCount = AtomicInteger()

            (1..threadCount)
                .map {
                    launch(Dispatchers.IO) {
                        barrier.await(5, TimeUnit.SECONDS)
                        val result =
                            lockManager.executeWithLock(
                                "concurrent-test-key",
                                leaseTime = Duration.ofSeconds(3),
                            ) {
                                lockAcquiredCount.incrementAndGet()
                                Thread.sleep(1000)
                            }
                        if (result == null) lockSkippedCount.incrementAndGet()
                    }
                }.joinAll()

            assertThat(lockAcquiredCount.get()).isEqualTo(1)
            assertThat(lockSkippedCount.get()).isEqualTo(threadCount - 1)
        }
}
