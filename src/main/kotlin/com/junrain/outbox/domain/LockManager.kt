package com.junrain.outbox.domain

import java.time.Duration

interface LockManager {
    /**
     * [key]에 대한 분산 락을 획득한 뒤 [action]을 실행한 결과를 반환합니다.
     *
     * 락 획득에 실패하면 [waitTime]이 0인 경우 null을 반환하고
     * 그렇지 않으면 예외를 던집니다.
     */
    fun <T> executeWithLock(
        key: String,
        waitTime: Duration = Duration.ZERO,
        leaseTime: Duration = Duration.ofSeconds(30),
        action: () -> T,
    ): T?
}
