package com.firkatdavletov.financeconsultante.operations.infrastructure

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient

@Configuration(proxyBeanMethods = false)
class OperationalMetricsConfiguration {
    @Bean
    @ConditionalOnProperty(
        prefix = "app.operations",
        name = ["metrics-enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun operationalMetrics(jdbc: JdbcClient): MeterBinder = JdbcOperationalMetrics(jdbc)
}

internal class JdbcOperationalMetrics(
    private val jdbc: JdbcClient,
) : MeterBinder {
    override fun bindTo(registry: MeterRegistry) {
        definitions.forEach { definition ->
            Gauge
                .builder(definition.name, this) { metrics -> metrics.read(definition.sql) }
                .description(definition.description)
                .baseUnit(definition.baseUnit)
                .register(registry)
        }
    }

    @Suppress("TooGenericExceptionCaught") // A scrape must return NaN instead of leaking SQL or financial data.
    private fun read(sql: String): Double =
        try {
            jdbc.sql(sql).query(Double::class.javaObjectType).single()
        } catch (failure: RuntimeException) {
            logger.warn("Operational metric query failed ({})", failure.javaClass.simpleName)
            Double.NaN
        }

    companion object {
        private val logger = LoggerFactory.getLogger(JdbcOperationalMetrics::class.java)
        private val definitions =
            listOf(
                MetricDefinition(
                    "financeconsultante.exchange.jobs.pending",
                    "Exchange-rate jobs waiting for completion.",
                    COUNT,
                    "SELECT count(*)::double precision FROM exchange_rate_job " +
                        "WHERE status IN ('PENDING', 'PROCESSING', 'RETRY')",
                ),
                MetricDefinition(
                    "financeconsultante.exchange.jobs.overdue.age",
                    "Age past due of the oldest unfinished exchange-rate job.",
                    SECONDS,
                    ageSql(
                        "exchange_rate_job",
                        "due_at",
                        "status IN ('PENDING', 'PROCESSING', 'RETRY') AND due_at <= clock_timestamp()",
                    ),
                ),
            )

        private fun ageSql(
            table: String,
            timestamp: String,
            condition: String,
        ): String =
            "SELECT coalesce(greatest(0, " +
                "extract(epoch FROM clock_timestamp() - min($timestamp))), 0)::double precision " +
                "FROM $table WHERE $condition"

        private const val COUNT = "messages"
        private const val SECONDS = "seconds"
    }
}

private data class MetricDefinition(
    val name: String,
    val description: String,
    val baseUnit: String,
    val sql: String,
)
