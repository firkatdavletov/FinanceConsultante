package com.firkatdavletov.financeconsultante

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@TestConfiguration(proxyBeanMethods = false)
class AccountingTestClock {
    @Bean
    @Primary
    fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-09-06T03:00:00Z"), ZoneOffset.UTC)
}
