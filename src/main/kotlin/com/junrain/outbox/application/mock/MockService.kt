package com.junrain.outbox.application.mock

import com.junrain.outbox.application.mock.command.CreateMockCommand
import com.junrain.outbox.application.mock.command.ProcessMockEventCommand
import com.junrain.outbox.domain.mock.Mock
import com.junrain.outbox.domain.mock.MockEventPublisher
import com.junrain.outbox.domain.mock.MockRepository
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service

@Service
class MockService(
    private val mockRepository: MockRepository,
    private val mockEventPublisher: MockEventPublisher,
) {
    @Transactional
    fun createMock(command: CreateMockCommand) {
        val mock =
            mockRepository.save(
                Mock(
                    content = command.content,
                ),
            )

        mockEventPublisher.publishCreatedMock(mock)
    }

    fun processEvent(command: ProcessMockEventCommand) {
        print("mock id: ${command.mockId}")
        print("mock content: ${command.content}")
    }
}
