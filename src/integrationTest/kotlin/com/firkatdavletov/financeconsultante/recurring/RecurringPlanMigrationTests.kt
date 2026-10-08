package com.firkatdavletov.financeconsultante.recurring

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

class RecurringPlanMigrationTests
    @Autowired
    constructor(
        private val dataSource: DataSource,
    ) : PostgresIntegrationTest() {
        @Test
        @Suppress("LongMethod") // Migration verification prepares a complete V6 budget and its plan relations.
        fun `V7 preserves V6 accounting data and constrains recurring plans`() {
            val schema = "recurring_migration_${UUID.randomUUID().toString().replace("-", "")}"
            val jdbc = JdbcTemplate(dataSource)
            try {
                val configuration =
                    Flyway
                        .configure()
                        .dataSource(dataSource)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .locations("classpath:db/migration")
                configuration.target(MigrationVersion.fromVersion("6")).load().migrate()
                val household = UUID.randomUUID()
                val member = UUID.randomUUID()
                val account = UUID.randomUUID()
                val expense = UUID.randomUUID()
                val income = UUID.randomUUID()
                val entry = UUID.randomUUID()
                jdbc.update("INSERT INTO $schema.household(id) VALUES (?)", household)
                jdbc.update(
                    "INSERT INTO $schema.member(id, household_id, telegram_user_id) VALUES (?, ?, 555)",
                    member,
                    household,
                )
                jdbc.update(
                    "INSERT INTO $schema.account(id, household_id, opening_balance, accounting_started_at) " +
                        "VALUES (?, ?, 10, '2026-09-01T00:00:00Z')",
                    account,
                    household,
                )
                jdbc.update(
                    "INSERT INTO $schema.category(id, household_id, name, kind) VALUES (?, ?, 'Связь', 'EXPENSE')",
                    expense,
                    household,
                )
                jdbc.update(
                    "INSERT INTO $schema.category(id, household_id, name, kind) " +
                        "VALUES (?, ?, 'Зарплата', 'INCOME')",
                    income,
                    household,
                )
                jdbc.update(
                    """INSERT INTO $schema.ledger_entry(id, household_id, account_id, accounting_started_at,
                category_id, direction, original_amount, original_currency, amount_kzt, occurred_at,
                source_key, actor_id, created_at, modified_at) VALUES (?, ?, ?, '2026-09-01T00:00:00Z',
                ?, 'INCOME', 12.34, 'KZT', 12.34, '2026-09-02T00:00:00Z', 'synthetic:v6', ?,
                '2026-09-02T01:00:00Z', '2026-09-02T01:00:00Z')""",
                    entry,
                    household,
                    account,
                    income,
                    member,
                )

                val current = configuration.target(MigrationVersion.fromVersion("7")).load()
                assertThat(current.migrate().migrationsExecuted).isEqualTo(1)
                assertThat(
                    jdbc.queryForObject("SELECT amount_kzt::text FROM $schema.ledger_entry", String::class.java),
                ).isEqualTo("12.34")
                val rule = UUID.randomUUID()
                val occurrence = UUID.randomUUID()
                val expected = UUID.randomUUID()
                jdbc.update(
                    """INSERT INTO $schema.recurring_rule(id, household_id, member_id, category_id, frequency,
                    schedule_value, amount, starts_on, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'MONTHLY', 31, 100, '2026-09-01', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""",
                    rule,
                    household,
                    member,
                    expense,
                )
                jdbc.update(
                    """INSERT INTO $schema.recurring_occurrence(id, household_id, rule_id, category_id,
                    planned_amount, due_on, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 100, '2026-09-30', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""",
                    occurrence,
                    household,
                    rule,
                    expense,
                )
                jdbc.update(
                    "INSERT INTO $schema.expected_income(id, household_id, member_id, category_id, planned_amount, " +
                        "due_on, created_at, updated_at) VALUES (?, ?, ?, ?, 100, '2026-09-10', " +
                        "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    expected,
                    household,
                    member,
                    income,
                )
                jdbc.update(
                    "INSERT INTO $schema.expected_income_match(household_id, expected_income_id, ledger_entry_id, " +
                        "created_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                    household,
                    expected,
                    entry,
                )
                assertThat(
                    jdbc.queryForObject("SELECT count(*) FROM $schema.recurring_occurrence", Long::class.java),
                ).isEqualTo(1)
                assertThatThrownBy {
                    jdbc.update(
                        """INSERT INTO $schema.recurring_rule(id, household_id, member_id, category_id, frequency,
                        schedule_value, amount, currency, starts_on, created_at, updated_at)
                        VALUES (?, ?, ?, ?, 'MONTHLY', 31, 100, 'RUB', '2026-09-01', CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP)""",
                        UUID.randomUUID(),
                        household,
                        member,
                        expense,
                    )
                }.isInstanceOf(RuntimeException::class.java)
                assertThat(current.migrate().migrationsExecuted).isZero()
            } finally {
                jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
            }
        }

        @Test
        @Suppress("LongMethod") // Migration verification prepares a complete V17 occurrence before upgrading.
        fun `V18 preserves occurrences and allows explicit paid status`() {
            val schema = "recurring_paid_migration_${UUID.randomUUID().toString().replace("-", "")}"
            val jdbc = JdbcTemplate(dataSource)
            try {
                val configuration =
                    Flyway
                        .configure()
                        .dataSource(dataSource)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .locations("classpath:db/migration")
                configuration.target(MigrationVersion.fromVersion("17")).load().migrate()
                val household = UUID.randomUUID()
                val member = UUID.randomUUID()
                val category = UUID.randomUUID()
                val rule = UUID.randomUUID()
                val occurrence = UUID.randomUUID()
                jdbc.update("INSERT INTO $schema.household(id) VALUES (?)", household)
                jdbc.update(
                    "INSERT INTO $schema.member(id, household_id, telegram_user_id) VALUES (?, ?, 556)",
                    member,
                    household,
                )
                jdbc.update(
                    "INSERT INTO $schema.category(id, household_id, name, kind) VALUES (?, ?, 'Жильё', 'EXPENSE')",
                    category,
                    household,
                )
                jdbc.update(
                    """INSERT INTO $schema.recurring_rule(id, household_id, member_id, category_id, frequency,
                    schedule_value, amount, starts_on, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'MONTHLY', 5, 40500, '2026-08-05', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""",
                    rule,
                    household,
                    member,
                    category,
                )
                jdbc.update(
                    """INSERT INTO $schema.recurring_occurrence(id, household_id, rule_id, category_id,
                    planned_amount, due_on, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 40500, '2026-08-05', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""",
                    occurrence,
                    household,
                    rule,
                    category,
                )

                val current = configuration.target(MigrationVersion.fromVersion("18")).load()
                assertThat(current.migrate().migrationsExecuted).isEqualTo(1)
                assertThat(
                    jdbc.queryForObject(
                        "SELECT status FROM $schema.recurring_occurrence WHERE id = ?",
                        String::class.java,
                        occurrence,
                    ),
                ).isEqualTo("SCHEDULED")
                assertThat(
                    jdbc.update(
                        "UPDATE $schema.recurring_occurrence SET status = 'PAID' WHERE id = ?",
                        occurrence,
                    ),
                ).isEqualTo(1)
                assertThatThrownBy {
                    jdbc.update(
                        "UPDATE $schema.recurring_occurrence SET status = 'UNKNOWN' WHERE id = ?",
                        occurrence,
                    )
                }.isInstanceOf(RuntimeException::class.java)
                assertThat(current.migrate().migrationsExecuted).isZero()
            } finally {
                jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
            }
        }
    }
