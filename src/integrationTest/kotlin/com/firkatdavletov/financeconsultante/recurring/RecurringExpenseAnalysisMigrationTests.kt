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

class RecurringExpenseAnalysisMigrationTests
    @Autowired
    constructor(
        private val dataSource: DataSource,
    ) : PostgresIntegrationTest() {
        @Test
        @Suppress("LongMethod") // Builds a representative V11 ledger before checking additive T15 constraints.
        fun `V12 preserves ledger and constrains owner confirmed candidates`() {
            val schema = "recurring_analysis_${UUID.randomUUID().toString().replace("-", "")}"
            val jdbc = JdbcTemplate(dataSource)
            try {
                val configuration =
                    Flyway
                        .configure()
                        .dataSource(dataSource)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .locations("classpath:db/migration")
                configuration.target(MigrationVersion.fromVersion("11")).load().migrate()
                val household = UUID.randomUUID()
                val member = UUID.randomUUID()
                val account = UUID.randomUUID()
                val category = UUID.randomUUID()
                val oldEntry = UUID.randomUUID()
                jdbc.update("INSERT INTO $schema.household(id) VALUES (?)", household)
                jdbc.update(
                    "INSERT INTO $schema.member(id, household_id, telegram_user_id) VALUES (?, ?, 555)",
                    member,
                    household,
                )
                jdbc.update(
                    "INSERT INTO $schema.account(id, household_id, opening_balance, accounting_started_at) " +
                        "VALUES (?, ?, 10000, '2026-06-01T00:00:00Z')",
                    account,
                    household,
                )
                jdbc.update(
                    "INSERT INTO $schema.category(id, household_id, name, kind) VALUES (?, ?, 'Связь', 'EXPENSE')",
                    category,
                    household,
                )
                jdbc.update(
                    """INSERT INTO $schema.ledger_entry(id, household_id, account_id, accounting_started_at,
                    category_id, direction, original_amount, original_currency, amount_kzt, occurred_at,
                    source_key, actor_id, created_at, modified_at) VALUES (?, ?, ?, '2026-06-01T00:00:00Z',
                    ?, 'EXPENSE', 1000, 'KZT', 1000, '2026-08-01T00:00:00Z', 'synthetic:v11', ?,
                    '2026-08-01T01:00:00Z', '2026-08-01T01:00:00Z')""",
                    oldEntry,
                    household,
                    account,
                    category,
                    member,
                )

                val current = configuration.target(MigrationVersion.fromVersion("12")).load()
                assertThat(current.migrate().migrationsExecuted).isEqualTo(1)
                assertThat(
                    jdbc.queryForObject(
                        "SELECT normalized_description FROM $schema.ledger_entry WHERE id = ?",
                        String::class.java,
                        oldEntry,
                    ),
                ).isNull()

                val candidate = UUID.randomUUID()
                jdbc.update(
                    """INSERT INTO $schema.recurring_expense_candidate(
                    id, household_id, member_id, category_id, normalized_description, frequency,
                    schedule_value, suggested_amount, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, 'стриминг', 'MONTHLY', 1, 1000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""",
                    candidate,
                    household,
                    member,
                    category,
                )
                jdbc.update(
                    "INSERT INTO $schema.recurring_expense_candidate_evidence(" +
                        "household_id, candidate_id, ledger_entry_id, created_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                    household,
                    candidate,
                    oldEntry,
                )
                assertThatThrownBy {
                    jdbc.update(
                        "UPDATE $schema.recurring_expense_candidate SET status = 'ACCEPTED' WHERE id = ?",
                        candidate,
                    )
                }.isInstanceOf(RuntimeException::class.java)
                assertThatThrownBy {
                    jdbc.update(
                        "UPDATE $schema.ledger_entry SET normalized_description = 'Не Нормализовано' WHERE id = ?",
                        oldEntry,
                    )
                }.isInstanceOf(RuntimeException::class.java)
                assertThat(current.migrate().migrationsExecuted).isZero()
            } finally {
                jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
            }
        }
    }
