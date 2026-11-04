package com.firkatdavletov.financeconsultante.exchange

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

class ExchangeRateMigrationTests
    @Autowired
    constructor(
        private val dataSource: DataSource,
    ) : PostgresIntegrationTest() {
        @Suppress("MagicNumber")
        @Test
        fun `V2 preserves V1 daily rates and backfills quote timestamp`() {
            val schema = "t02a_${UUID.randomUUID().toString().replace("-", "")}"
            val jdbc = JdbcTemplate(dataSource)
            val rateDate = LocalDate.parse("2039-12-31")
            val fetchedAt = Instant.parse("2039-12-31T02:00:00Z")
            try {
                val v1 = flyway(schema, MigrationVersion.fromVersion("1"))
                assertThat(v1.migrate().migrationsExecuted).isEqualTo(1)
                jdbc.update(
                    "INSERT INTO $schema.daily_exchange_rate (rate_date, rub_kzt, source, fetched_at) " +
                        "VALUES (?, ?, ?, ?)",
                    Date.valueOf(rateDate),
                    BigDecimal("5.12500000"),
                    "TEST",
                    Timestamp.from(fetchedAt),
                )

                val current = flyway(schema)
                assertThat(current.migrate().migrationsExecuted).isEqualTo(31)
                val timestamps =
                    jdbc.queryForMap(
                        "SELECT fetched_at, quoted_at FROM $schema.daily_exchange_rate WHERE rate_date = ?",
                        Date.valueOf(rateDate),
                    )
                assertThat((timestamps.getValue("fetched_at") as Timestamp).toInstant()).isEqualTo(fetchedAt)
                assertThat((timestamps.getValue("quoted_at") as Timestamp).toInstant()).isEqualTo(fetchedAt)
                assertThat(
                    current.info().applied().mapNotNull { it.version?.version },
                ).containsExactlyElementsOf((1..32).map { it.toString() })
            } finally {
                jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
            }
        }

        private fun flyway(
            schema: String,
            target: MigrationVersion? = null,
        ): Flyway {
            val configuration =
                Flyway
                    .configure()
                    .dataSource(dataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
            if (target != null) configuration.target(target)
            return configuration.load()
        }
    }
