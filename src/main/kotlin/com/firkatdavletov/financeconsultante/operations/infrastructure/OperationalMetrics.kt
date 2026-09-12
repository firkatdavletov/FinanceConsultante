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
                    "financeconsultante.telegram.inbox.pending",
                    "Telegram inbox messages waiting for local or OpenAI processing.",
                    COUNT,
                    "SELECT count(*)::double precision FROM telegram_inbox " +
                        "WHERE status IN ('RECEIVED', 'OPENAI_PENDING')",
                ),
                MetricDefinition(
                    "financeconsultante.telegram.inbox.oldest.age",
                    "Age of the oldest Telegram inbox message waiting for processing.",
                    SECONDS,
                    ageSql("telegram_inbox", "created_at", "status IN ('RECEIVED', 'OPENAI_PENDING')"),
                ),
                MetricDefinition(
                    "financeconsultante.telegram.outbox.pending",
                    "Telegram outbox messages waiting for delivery.",
                    COUNT,
                    "SELECT count(*)::double precision FROM telegram_outbox " +
                        "WHERE status IN ('PENDING', 'SENDING')",
                ),
                MetricDefinition(
                    "financeconsultante.telegram.outbox.failed",
                    "Telegram outbox messages with a permanent delivery failure.",
                    COUNT,
                    "SELECT count(*)::double precision FROM telegram_outbox WHERE status = 'FAILED'",
                ),
                MetricDefinition(
                    "financeconsultante.report.jobs.pending",
                    "Daily, weekly, and monthly report jobs not yet sent or skipped.",
                    COUNT,
                    "SELECT count(*)::double precision FROM daily_report_job " +
                        "WHERE status IN ('SCHEDULED', 'PROCESSING', 'QUEUED')",
                ),
                MetricDefinition(
                    "financeconsultante.report.jobs.failed",
                    "Daily, weekly, and monthly report jobs that exhausted retries.",
                    COUNT,
                    "SELECT count(*)::double precision FROM daily_report_job WHERE status = 'FAILED'",
                ),
                MetricDefinition(
                    "financeconsultante.report.jobs.overdue.age",
                    "Age past schedule of the oldest report job that should already have run.",
                    SECONDS,
                    ageSql(
                        "daily_report_job",
                        "scheduled_for",
                        "status IN ('SCHEDULED', 'PROCESSING', 'QUEUED') AND scheduled_for <= clock_timestamp()",
                    ),
                ),
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
                MetricDefinition(
                    "financeconsultante.openai.jobs.pending",
                    "OpenAI recognition jobs waiting for a terminal result.",
                    COUNT,
                    "SELECT count(*)::double precision FROM openai_recognition_job " +
                        "WHERE status IN ('PENDING', 'PROCESSING')",
                ),
                MetricDefinition(
                    "financeconsultante.openai.jobs.oldest.age",
                    "Age of the oldest OpenAI recognition job waiting for a terminal result.",
                    SECONDS,
                    ageSql("openai_recognition_job", "created_at", "status IN ('PENDING', 'PROCESSING')"),
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
