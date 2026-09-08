package com.firkatdavletov.financeconsultante

import com.firkatdavletov.financeconsultante.exchange.application.ExchangeRateWorker
import com.firkatdavletov.financeconsultante.telegram.application.TelegramDeliveryWorker
import com.firkatdavletov.financeconsultante.telegram.application.TelegramPoller
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.core.env.Environment

class FinanceConsultanteApplicationTests
    @Autowired
    constructor(
        private val environment: Environment,
        private val applicationContext: ApplicationContext,
    ) : PostgresIntegrationTest() {
        @Test
        fun `context starts with external integrations disabled`() {
            assertThat(environment.activeProfiles).containsExactly("test")
            assertThat(environment.getProperty("app.telegram.enabled", Boolean::class.java)).isFalse()
            assertThat(environment.getProperty("app.openai.enabled", Boolean::class.java)).isFalse()
            assertThat(environment.getProperty("app.notifications.enabled", Boolean::class.java)).isFalse()
            assertThat(environment.getProperty("app.exchange-rates.enabled", Boolean::class.java)).isFalse()
            assertThat(applicationContext.getBeansOfType(ExchangeRateWorker::class.java)).isEmpty()
            assertThat(applicationContext.getBeansOfType(TelegramPoller::class.java)).isEmpty()
            assertThat(applicationContext.getBeansOfType(TelegramDeliveryWorker::class.java)).isEmpty()
        }
    }
