package com.junrain.outbox.domain.mock

interface MockEventPublisher {
    /**
     * Mock 생성 이벤트를 발급합니다.
     */
    fun publishCreatedMock(mock: Mock)
}
