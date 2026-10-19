package com.firkatdavletov.financeconsultante.mobileapi

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import javax.sql.DataSource

class OfflineSyncMigrationTests
    @Autowired
    constructor(
        private val dataSource: DataSource,
    ) : PostgresIntegrationTest() {
        @Test
        // Preserve V18 data and verify backfill and runtime triggers.
        @Suppress("LongMethod", "MagicNumber")
        fun `V19 and V20 backfill ledger plans and payment links without changing financial data`() {
            val schema = "offline_${UUID.randomUUID().toString().replace("-", "")}"
            val jdbc = JdbcTemplate(dataSource)
            try {
                val config =
                    Flyway
                        .configure()
                        .dataSource(dataSource)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .locations("classpath:db/migration")
                config.target(MigrationVersion.fromVersion("18")).load().migrate()
                val household = UUID.randomUUID()
                val member = UUID.randomUUID()
                val account = UUID.randomUUID()
                val category = UUID.randomUUID()
                val entry = UUID.randomUUID()
                jdbc.update("INSERT INTO $schema.household(id) VALUES (?)", household)
                jdbc.update(
                    "INSERT INTO $schema.member(id, household_id, telegram_user_id) VALUES (?, ?, 88771)",
                    member,
                    household,
                )
                jdbc.update(
                    "INSERT INTO $schema.account(id, household_id, opening_balance, accounting_started_at) " +
                        "VALUES (?, ?, 0, '2026-09-01T00:00:00Z')",
                    account,
                    household,
                )
                jdbc.update(
                    "INSERT INTO $schema.category(id, household_id, name, kind) VALUES (?, ?, 'Тест', 'EXPENSE')",
                    category,
                    household,
                )
                jdbc.update(
                    """
                    INSERT INTO $schema.ledger_entry(id, household_id, account_id,
                            accounting_started_at, category_id,
                        direction, original_amount, original_currency, amount_kzt, occurred_at, source_key, actor_id, created_at)
                    VALUES (?, ?, ?, '2026-09-01T00:00:00Z', ?, 'EXPENSE', 19.01, 'KZT', 19.01,
                        '2026-09-02T00:00:00Z', 'test:migration:offline', ?, '2026-09-03T00:00:00Z')
                    """.trimIndent(),
                    entry,
                    household,
                    account,
                    category,
                    member,
                )
                val before =
                    jdbc.queryForObject(
                        "SELECT to_jsonb(l)::text FROM $schema.ledger_entry l",
                        String::class.java,
                    )
                assertThat(
                    config
                        .target(MigrationVersion.fromVersion("19"))
                        .load()
                        .migrate()
                        .migrationsExecuted,
                ).isEqualTo(1)
                assertThat(
                    jdbc.queryForObject("SELECT to_jsonb(l)::text FROM $schema.ledger_entry l", String::class.java),
                ).isEqualTo(before)
                assertThat(
                    jdbc.queryForObject("SELECT count(*) FROM $schema.mobile_sync_change", Long::class.java),
                ).isEqualTo(2)
                jdbc.update("UPDATE $schema.category SET name = 'После миграции' WHERE id = ?", category)
                assertThat(
                    jdbc.queryForObject("SELECT revision FROM $schema.mobile_sync_state", Long::class.java),
                ).isEqualTo(3)
                assertThat(
                    jdbc.queryForObject(
                        "SELECT snapshot ->> 'name' FROM $schema.mobile_sync_change WHERE revision = 3",
                        String::class.java,
                    ),
                ).isEqualTo("После миграции")
                val rule = UUID.randomUUID()
                val occurrence = UUID.randomUUID()
                jdbc.update(
                    """
                    INSERT INTO $schema.recurring_rule(id, household_id, member_id, category_id,
                        frequency, schedule_value, amount, starts_on, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'MONTHLY', 31, 10.01, '2026-09-01', now(), now())
                    """.trimIndent(),
                    rule,
                    household,
                    member,
                    category,
                )
                jdbc.update(
                    """
                    INSERT INTO $schema.recurring_occurrence(id, household_id, rule_id, category_id,
                        planned_amount, due_on, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 10.01, '2026-09-30', now(), now())
                    """.trimIndent(),
                    occurrence,
                    household,
                    rule,
                    category,
                )
                jdbc.update(
                    "INSERT INTO $schema.recurring_occurrence_payment " +
                        "(household_id, occurrence_id, ledger_entry_id, created_at) VALUES (?, ?, ?, now())",
                    household,
                    occurrence,
                    entry,
                )
                val ruleBefore =
                    jdbc.queryForObject(
                        "SELECT to_jsonb(r)::text FROM $schema.recurring_rule r",
                        String::class.java,
                    )
                assertThat(
                    config
                        .target(MigrationVersion.fromVersion("20"))
                        .load()
                        .migrate()
                        .migrationsExecuted,
                ).isEqualTo(1)
                assertThat(
                    jdbc.queryForObject("SELECT to_jsonb(r)::text FROM $schema.recurring_rule r", String::class.java),
                ).isEqualTo(ruleBefore)
                assertThat(
                    jdbc.queryForObject("SELECT to_jsonb(l)::text FROM $schema.ledger_entry l", String::class.java),
                ).isEqualTo(before)
                assertThat(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM $schema.mobile_sync_change " +
                            "WHERE kind IN ('RECURRING_RULE', 'RECURRING_OCCURRENCE', 'OCCURRENCE_PAYMENT')",
                        Long::class.java,
                    ),
                ).isEqualTo(3)
                jdbc.update("DELETE FROM $schema.recurring_occurrence_payment WHERE occurrence_id = ?", occurrence)
                assertThat(
                    jdbc.queryForObject(
                        "SELECT deleted FROM $schema.mobile_sync_change ORDER BY revision DESC LIMIT 1",
                        Boolean::class.java,
                    ),
                ).isTrue()
                assertThat(
                    config
                        .target(MigrationVersion.fromVersion("21"))
                        .load()
                        .migrate()
                        .migrationsExecuted,
                ).isEqualTo(1)
                assertThat(
                    jdbc.queryForObject(
                        "SELECT snapshot ->> 'opening_balance' FROM $schema.mobile_sync_change " +
                            "WHERE kind = 'BUDGET_SETTINGS'",
                        String::class.java,
                    ),
                ).isEqualTo("0")
                jdbc.update("UPDATE $schema.household SET cash_reserve = 12.34 WHERE id = ?", household)
                assertThat(
                    jdbc.queryForObject(
                        "SELECT snapshot ->> 'protected_reserve' FROM $schema.mobile_sync_change " +
                            "ORDER BY revision DESC LIMIT 1",
                        String::class.java,
                    ),
                ).isEqualTo("12.34")
                assertThat(
                    jdbc.queryForObject("SELECT to_jsonb(l)::text FROM $schema.ledger_entry l", String::class.java),
                ).isEqualTo(before)
            } finally {
                jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
            }
        }
    }
