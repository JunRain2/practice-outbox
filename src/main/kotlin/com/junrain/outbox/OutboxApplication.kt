package com.junrain.outbox

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class OutboxApplication

fun main(args: Array<String>) {
    runApplication<OutboxApplication>(*args)
}
