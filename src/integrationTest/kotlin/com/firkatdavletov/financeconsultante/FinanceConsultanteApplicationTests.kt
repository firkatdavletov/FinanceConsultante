package com.firkatdavletov.financeconsultante

import com.firkatdavletov.financeconsultante.exchange.application.ExchangeRateWorker
import com.firkatdavletov.financeconsultante.telegram.application.TelegramDeliveryWorker
import com.firkatdavletov.financeconsultante.telegram.application.TelegramPoller
import io.micrometer.core.instrument.MeterRegistry
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
        private val meterRegistry: MeterRegistry,
    ) : PostgresIntegrationTest() {
        private companion object {
            const val OPERATIONAL_GAUGE_COUNT = 11
        }

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

        @Test
        fun `operational gauges query the migrated schema without exposing identifiers`() {
            val gauges =
                meterRegistry.meters
                    .filter { it.id.name.startsWith("financeconsultante.") }
                    .map { meterRegistry.find(it.id.name).gauge() }

            assertThat(gauges).hasSize(OPERATIONAL_GAUGE_COUNT).doesNotContainNull()
            assertThat(gauges.map { checkNotNull(it).value() }).allMatch { it.isFinite() && it >= 0 }
            assertThat(gauges.flatMap { checkNotNull(it).id.tags }.map { it.key })
                .doesNotContain("owner", "telegram_id", "household", "member")
        }
    }
