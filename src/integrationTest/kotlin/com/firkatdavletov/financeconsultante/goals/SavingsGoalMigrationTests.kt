package com.firkatdavletov.financeconsultante.goals

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

class SavingsGoalMigrationTests
    @Autowired
    constructor(
        private val dataSource: DataSource,
    ) : PostgresIntegrationTest() {
        @Test
        @Suppress("LongMethod") // Builds representative V10 accounting data before validating the additive migration.
        fun `V11 preserves V10 data and constrains goal allocations`() {
            val schema = "goal_migration_${UUID.randomUUID().toString().replace("-", "")}"
            val jdbc = JdbcTemplate(dataSource)
            try {
                val configuration =
                    Flyway
                        .configure()
                        .dataSource(dataSource)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .locations("classpath:db/migration")
                configuration.target(MigrationVersion.fromVersion("10")).load().migrate()
                val household = UUID.randomUUID()
                val member = UUID.randomUUID()
                val account = UUID.randomUUID()
                val category = UUID.randomUUID()
                val entry = UUID.randomUUID()
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
                    "INSERT INTO $schema.category(id, household_id, name, kind) VALUES (?, ?, 'Покупка', 'EXPENSE')",
                    category,
                    household,
                )
                jdbc.update(
                    """INSERT INTO $schema.ledger_entry(id, household_id, account_id, accounting_started_at,
                    category_id, direction, original_amount, original_currency, amount_kzt, occurred_at,
                    source_key, actor_id, created_at, modified_at) VALUES (?, ?, ?, '2026-09-01T00:00:00Z',
                    ?, 'EXPENSE', 20, 'KZT', 20, '2026-09-02T00:00:00Z', 'synthetic:v10', ?,
                    '2026-09-02T01:00:00Z', '2026-09-02T01:00:00Z')""",
                    entry,
                    household,
                    account,
                    category,
                    member,
                )

                val current = configuration.target(MigrationVersion.fromVersion("11")).load()
                assertThat(current.migrate().migrationsExecuted).isEqualTo(1)
                assertThat(
                    jdbc.queryForObject("SELECT amount_kzt::text FROM $schema.ledger_entry", String::class.java),
                ).isEqualTo("20")

                val goal = UUID.randomUUID()
                jdbc.update(
                    """INSERT INTO $schema.savings_goal(
                    id, household_id, member_id, name, target_amount, deadline, priority, allocated_amount,
                    source_key, created_at, updated_at
                    ) VALUES (?, ?, ?, 'Резерв', 100, '2026-12-31', 1, 30, 'synthetic:goal',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""",
                    goal,
                    household,
                    member,
                )
                jdbc.update(
                    """INSERT INTO $schema.savings_goal_allocation(
                    household_id, goal_id, action, amount, source_key, created_at
                    ) VALUES (?, ?, 'RESERVED', 30, 'synthetic:reserve', CURRENT_TIMESTAMP)""",
                    household,
                    goal,
                )
                assertThat(
                    jdbc.queryForObject(
                        "SELECT allocated_amount::text FROM $schema.savings_goal WHERE id = ?",
                        String::class.java,
                        goal,
                    ),
                ).isEqualTo("30")
                assertThatThrownBy {
                    jdbc.update(
                        """INSERT INTO $schema.savings_goal(
                        id, household_id, member_id, name, target_amount, deadline, priority, allocated_amount,
                        source_key, created_at, updated_at
                        ) VALUES (?, ?, ?, 'Недопустимо', 100, '2026-12-31', 1, 101, 'synthetic:invalid',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""",
                        UUID.randomUUID(),
                        household,
                        member,
                    )
                }.isInstanceOf(RuntimeException::class.java)
                assertThatThrownBy {
                    jdbc.update(
                        """INSERT INTO $schema.savings_goal_allocation(
                        household_id, goal_id, action, amount, source_key, created_at
                        ) VALUES (?, ?, 'SPENT', 1, 'synthetic:invalid-spend', CURRENT_TIMESTAMP)""",
                        household,
                        goal,
                    )
                }.isInstanceOf(RuntimeException::class.java)
                assertThat(current.migrate().migrationsExecuted).isZero()
            } finally {
                jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
            }
        }
    }
