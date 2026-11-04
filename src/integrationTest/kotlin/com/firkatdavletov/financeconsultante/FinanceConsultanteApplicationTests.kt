package com.firkatdavletov.financeconsultante

import com.firkatdavletov.financeconsultante.exchange.application.ExchangeRateWorker
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
            const val OPERATIONAL_GAUGE_COUNT = 2
        }

        @Test
        fun `context starts with external integrations disabled`() {
            assertThat(environment.activeProfiles).containsExactly("test")
            assertThat(environment.getProperty("app.exchange-rates.enabled", Boolean::class.java)).isFalse()
            assertThat(applicationContext.getBeansOfType(ExchangeRateWorker::class.java)).isEmpty()
            assertThat(applicationContext.beanDefinitionNames.mapNotNull { applicationContext.getType(it)?.name })
                .noneMatch { it.startsWith("com.firkatdavletov.financeconsultante.telegram.") }
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
