package com.junrain.outbox.support

import com.redis.testcontainers.RedisContainer
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.testcontainers.containers.MySQLContainer

@Configuration
class TestcontainersConfig {
    @Bean
    @ServiceConnection
    fun mysqlContainer(): MySQLContainer<*> = MySQLContainer("mysql:8.0")

    @Bean
    @ServiceConnection
    fun redisContainer(): RedisContainer = RedisContainer("redis:7")
}
