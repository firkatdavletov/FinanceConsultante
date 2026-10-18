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
        fun `V19 backfills previous ledger without changing financial data and captures subsequent writes`() {
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
            } finally {
                jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
            }
        }
    }
