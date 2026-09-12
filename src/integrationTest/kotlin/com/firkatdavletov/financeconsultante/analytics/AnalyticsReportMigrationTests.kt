package com.firkatdavletov.financeconsultante.analytics

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import javax.sql.DataSource

class AnalyticsReportMigrationTests
    @Autowired
    constructor(
        private val dataSource: DataSource,
    ) : PostgresIntegrationTest() {
        @Test
        fun `V9 preserves daily jobs and permits distinct report types for one period`() {
            val schema = "analytics_report_migration_${UUID.randomUUID().toString().replace("-", "")}"
            val jdbc = JdbcTemplate(dataSource)
            try {
                val configuration =
                    Flyway
                        .configure()
                        .dataSource(dataSource)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .locations("classpath:db/migration")
                configuration.target(MigrationVersion.fromVersion("8")).load().migrate()
                val household = UUID.randomUUID()
                val member = UUID.randomUUID()
                jdbc.update("INSERT INTO $schema.household(id) VALUES (?)", household)
                jdbc.update(
                    "INSERT INTO $schema.member(id, household_id, telegram_user_id) VALUES (?, ?, 404)",
                    member,
                    household,
                )
                jdbc.update(
                    "INSERT INTO $schema.account(id, household_id, opening_balance, accounting_started_at) " +
                        "VALUES (?, ?, 0, '2026-01-01T00:00:00Z')",
                    UUID.randomUUID(),
                    household,
                )
                insertJob(jdbc, schema, household, member, "DAILY")

                val current = configuration.target(MigrationVersion.fromVersion("9")).load()
                assertThat(current.migrate().migrationsExecuted).isEqualTo(1)
                insertJob(jdbc, schema, household, member, "WEEKLY")
                insertJob(jdbc, schema, household, member, "MONTHLY")

                assertThat(
                    jdbc.queryForList("SELECT report_type FROM $schema.daily_report_job ORDER BY report_type"),
                ).hasSize(REPORT_TYPE_COUNT)
                assertThatThrownBy { insertJob(jdbc, schema, household, member, "YEARLY") }
                    .isInstanceOf(RuntimeException::class.java)
                assertThat(current.migrate().migrationsExecuted).isZero()
            } finally {
                jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
            }
        }

        private fun insertJob(
            jdbc: JdbcTemplate,
            schema: String,
            household: UUID,
            member: UUID,
            type: String,
        ) {
            jdbc.update(
                """
                INSERT INTO $schema.daily_report_job(
                    id, household_id, member_id, recipient_id, bot_id, report_type, period_start,
                    scheduled_for, catch_up_until, next_attempt_at, created_at, updated_at
                ) VALUES (?, ?, ?, 404, 303, ?, '2026-01-01', '2026-02-01T02:00:00Z',
                    '2026-02-03T02:00:00Z', '2026-02-01T02:00:00Z', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.trimIndent(),
                UUID.randomUUID(),
                household,
                member,
                type,
            )
        }

        companion object {
            private const val REPORT_TYPE_COUNT = 3
        }
    }
