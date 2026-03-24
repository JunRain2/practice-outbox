package com.junrain.outbox.domain.mock

import com.junrain.outbox.domain.BaseEntity
import jakarta.persistence.Entity

@Entity
class Mock(
    val content: String,
) : BaseEntity()
