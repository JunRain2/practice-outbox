package com.junrain.outbox.domain.mock

import org.springframework.data.jpa.repository.JpaRepository

interface MockRepository : JpaRepository<Mock, Long>
