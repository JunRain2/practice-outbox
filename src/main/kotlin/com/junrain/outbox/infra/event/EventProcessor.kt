package com.junrain.outbox.infra.event

interface EventProcessor {
    /**
     * 이벤트를 실행하고 결과에 따라 성공/실패 처리합니다.
     * 동일 이벤트는 다중 서버에서도 하나의 스레드만 처리함을 보장합니다.
     */
    fun process(eventId: Long)
}
