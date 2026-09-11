package com.firkatdavletov.financeconsultante.budget

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

class DailyReportMigrationTests
    @Autowired
    constructor(
        private val dataSource: DataSource,
    ) : PostgresIntegrationTest() {
        @Test
        @Suppress("LongMethod") // Builds a representative V7 budget, plan, inbox, and outbox before migration.
        fun `V8 preserves V7 data and enforces one report per recipient and date`() {
            val schema = "daily_report_migration_${UUID.randomUUID().toString().replace("-", "")}"
            val jdbc = JdbcTemplate(dataSource)
            try {
                val configuration =
                    Flyway
                        .configure()
                        .dataSource(dataSource)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .locations("classpath:db/migration")
                configuration.target(MigrationVersion.fromVersion("7")).load().migrate()
                val household = UUID.randomUUID()
                val member = UUID.randomUUID()
                val account = UUID.randomUUID()
                val expense = UUID.randomUUID()
                val rule = UUID.randomUUID()
                jdbc.update("INSERT INTO $schema.household(id, cash_reserve) VALUES (?, 50)", household)
                jdbc.update(
                    "INSERT INTO $schema.member(id, household_id, telegram_user_id) VALUES (?, ?, 555)",
                    member,
                    household,
                )
                jdbc.update(
                    "INSERT INTO $schema.account(id, household_id, opening_balance, accounting_started_at) " +
                        "VALUES (?, ?, 300, '2026-09-01T00:00:00Z')",
                    account,
                    household,
                )
                jdbc.update(
                    "INSERT INTO $schema.category(id, household_id, name, kind) VALUES (?, ?, 'Связь', 'EXPENSE')",
                    expense,
                    household,
                )
                jdbc.update(
                    "INSERT INTO $schema.recurring_rule(id, household_id, member_id, category_id, frequency, " +
                        "schedule_value, amount, starts_on, created_at, updated_at) VALUES " +
                        "(?, ?, ?, ?, 'MONTHLY', 6, 100, '2026-09-01', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    rule,
                    household,
                    member,
                    expense,
                )
                jdbc.update("INSERT INTO $schema.telegram_polling_state(bot_id) VALUES (123456)")
                jdbc.update(
                    "INSERT INTO $schema.telegram_inbox(bot_id, update_id, owner_id, text, status) " +
                        "VALUES (123456, 1, 555, 'synthetic', 'RECEIVED')",
                )
                jdbc.update(
                    "INSERT INTO $schema.telegram_outbox(inbox_id, bot_id, owner_id, text) " +
                        "SELECT id, 123456, 555, 'existing reply' FROM $schema.telegram_inbox",
                )

                val current = configuration.target(MigrationVersion.fromVersion("8")).load()
                assertThat(current.migrate().migrationsExecuted).isEqualTo(1)
                assertThat(
                    jdbc.queryForObject("SELECT text FROM $schema.telegram_outbox", String::class.java),
                ).isEqualTo("existing reply")
                assertThat(
                    jdbc.queryForObject("SELECT settings_version FROM $schema.household", Long::class.java),
                ).isZero()
                assertThat(
                    jdbc.queryForObject("SELECT count(*) FROM $schema.recurring_rule", Long::class.java),
                ).isEqualTo(1)

                val job = UUID.randomUUID()
                jdbc.update(
                    """INSERT INTO $schema.daily_report_job(
                    id, household_id, member_id, recipient_id, bot_id, period_start, scheduled_for,
                    catch_up_until, next_attempt_at, created_at, updated_at
                    ) VALUES (?, ?, ?, 555, 123456, '2026-09-06', '2026-09-06T02:00:00Z',
                    '2026-09-06T07:00:00Z', '2026-09-06T02:00:00Z', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""",
                    job,
                    household,
                    member,
                )
                jdbc.update(
                    "INSERT INTO $schema.telegram_outbox(report_job_id, bot_id, owner_id, text) " +
                        "VALUES (?, 123456, 555, 'daily report')",
                    job,
                )
                assertThatThrownBy {
                    jdbc.update(
                        """INSERT INTO $schema.daily_report_job(
                        id, household_id, member_id, recipient_id, bot_id, period_start, scheduled_for,
                        catch_up_until, next_attempt_at, created_at, updated_at
                        ) VALUES (?, ?, ?, 555, 123456, '2026-09-06', '2026-09-06T02:00:00Z',
                        '2026-09-06T07:00:00Z', '2026-09-06T02:00:00Z', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""",
                        UUID.randomUUID(),
                        household,
                        member,
                    )
                }.isInstanceOf(RuntimeException::class.java)
                assertThat(current.migrate().migrationsExecuted).isZero()
            } finally {
                jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
            }
        }
    }
