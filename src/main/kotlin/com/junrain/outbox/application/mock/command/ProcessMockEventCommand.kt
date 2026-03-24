package com.junrain.outbox.application.mock.command

data class ProcessMockEventCommand(
    val mockId: Long,
    val content: String,
)
