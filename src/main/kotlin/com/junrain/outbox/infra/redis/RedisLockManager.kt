package com.junrain.outbox.infra.redis

import com.junrain.outbox.domain.LockManager
import org.redisson.api.RedissonClient
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.TimeUnit

@Component
class RedisLockManager(
    private val redissonClient: RedissonClient,
) : LockManager {
    override fun <T> executeWithLock(
        key: String,
        waitTime: Duration,
        leaseTime: Duration,
        action: () -> T,
    ): T? {
        val lock = redissonClient.getLock(key)
        val acquired =
            lock.tryLock(
                waitTime.toMillis(),
                leaseTime.toMillis(),
                TimeUnit.MILLISECONDS,
            )
        if (!acquired) {
            // FOR UPDATE SKIP LOCKED
            if (waitTime.isZero) {
                return null
            } else {
                error("락 획득에 실패했습니다. key: $key")
            }
        }
        try {
            return action()
        } finally {
            if (lock.isHeldByCurrentThread) {
                lock.unlock()
            }
        }
    }
}
