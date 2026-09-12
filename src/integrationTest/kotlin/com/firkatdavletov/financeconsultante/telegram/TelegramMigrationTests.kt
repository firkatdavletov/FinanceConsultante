package com.firkatdavletov.financeconsultante.telegram

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.util.UUID
import javax.sql.DataSource

class TelegramMigrationTests
    @Autowired
    constructor(
        private val dataSource: DataSource,
    ) : PostgresIntegrationTest() {
        @Test
        fun `V3 preserves existing budget and backfills reserve without changing opening`() {
            val schema = "telegram_migration_${UUID.randomUUID().toString().replace("-", "")}"
            val id = UUID.randomUUID()
            val jdbc = JdbcTemplate(dataSource)
            try {
                val configuration =
                    Flyway
                        .configure()
                        .dataSource(dataSource)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .locations("classpath:db/migration")
                configuration.target(MigrationVersion.fromVersion("2")).load().migrate()
                jdbc.update("INSERT INTO $schema.household (id, report_time) VALUES (?, '09:15')", id)
                jdbc.update(
                    "INSERT INTO $schema.member (id, household_id, telegram_user_id) VALUES (?, ?, 123)",
                    UUID.randomUUID(),
                    id,
                )
                jdbc.update(
                    """INSERT INTO $schema.account (id, household_id, opening_balance, accounting_started_at)
                VALUES (?, ?, 1234.50, '2026-03-01T00:00:00Z')""",
                    UUID.randomUUID(),
                    id,
                )
                val current = configuration.target(MigrationVersion.LATEST).load()
                val expectedNewMigrations = 7
                assertThat(current.migrate().migrationsExecuted).isEqualTo(expectedNewMigrations)
                assertThat(jdbc.queryForObject("SELECT cash_reserve FROM $schema.household", BigDecimal::class.java))
                    .isEqualByComparingTo("0")
                assertThat(jdbc.queryForObject("SELECT opening_balance FROM $schema.account", BigDecimal::class.java))
                    .isEqualByComparingTo("1234.50")
                assertThat(
                    jdbc.queryForObject("SELECT report_time::text FROM $schema.household", String::class.java),
                ).isEqualTo("09:15:00")
                assertThat(current.migrate().migrationsExecuted).isZero()
                assertThat(jdbc.queryForObject("SELECT count(*) FROM $schema.member", Long::class.java)).isEqualTo(1)
            } finally {
                jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
            }
        }
    }
