package com.firkatdavletov.financeconsultante.telegram

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import javax.sql.DataSource

class TransactionDraftMigrationTests
    @Autowired
    constructor(
        private val dataSource: DataSource,
    ) : PostgresIntegrationTest() {
        @Test
        @Suppress("LongMethod") // A migration check must prepare and compare the complete V3 state.
        fun `V4 preserves ledger audit inbox offset and ready reply from V3`() {
            val schema = "draft_migration_${UUID.randomUUID().toString().replace("-", "")}"
            val jdbc = JdbcTemplate(dataSource)
            try {
                val config =
                    Flyway
                        .configure()
                        .dataSource(dataSource)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .locations("classpath:db/migration")
                config.target(MigrationVersion.fromVersion("3")).load().migrate()
                val household = UUID.randomUUID()
                val member = UUID.randomUUID()
                val account = UUID.randomUUID()
                val category = UUID.randomUUID()
                val entry = UUID.randomUUID()
                jdbc.update("INSERT INTO $schema.household(id) VALUES (?)", household)
                jdbc.update(
                    "INSERT INTO $schema.member(id, household_id, telegram_user_id) VALUES (?, ?, 555)",
                    member,
                    household,
                )
                jdbc.update(
                    "INSERT INTO $schema.account(id, household_id, opening_balance, accounting_started_at) " +
                        "VALUES (?, ?, 12.34, '2026-09-01T00:00:00Z')",
                    account,
                    household,
                )
                jdbc.update(
                    "INSERT INTO $schema.category(id, household_id, name, kind) VALUES (?, ?, 'Продукты', 'EXPENSE')",
                    category,
                    household,
                )
                jdbc.update(
                    """INSERT INTO $schema.ledger_entry(id, household_id, account_id, accounting_started_at,
                category_id, direction, original_amount, original_currency, amount_kzt, occurred_at,
                source_key, actor_id, created_at) VALUES (?, ?, ?, '2026-09-01T00:00:00Z', ?, 'EXPENSE',
                1.23, 'KZT', 1.23, '2026-09-02T00:00:00Z', 'synthetic:v3', ?, '2026-09-02T01:00:00Z')""",
                    entry,
                    household,
                    account,
                    category,
                    member,
                )
                jdbc.update(
                    "INSERT INTO $schema.entry_revision(household_id, entry_id, version, actor_id, action, " +
                        "snapshot, created_at) " +
                        "SELECT household_id, id, version, actor_id, 'CREATED', to_jsonb(l), created_at FROM " +
                        "$schema.ledger_entry l",
                )
                jdbc.update("INSERT INTO $schema.telegram_polling_state(bot_id, next_offset) VALUES (123456, 3)")
                jdbc.update(
                    "INSERT INTO $schema.telegram_inbox(bot_id, update_id, owner_id, text, status) " +
                        "VALUES (123456, 1, 555, 'продукты 10', 'RECEIVED')",
                )
                jdbc.update(
                    "INSERT INTO $schema.telegram_inbox(bot_id, update_id, owner_id, status) " +
                        "VALUES (123456, 2, 555, 'PROCESSED')",
                )
                jdbc.update(
                    "INSERT INTO $schema.telegram_outbox(inbox_id, bot_id, owner_id, text) " +
                        "VALUES (2, 123456, 555, 'Сохранённый ответ')",
                )
                val ledgerBefore =
                    jdbc.queryForObject(
                        "SELECT to_jsonb(l)::text FROM $schema.ledger_entry l",
                        String::class.java,
                    )
                val auditBefore =
                    jdbc.queryForObject(
                        "SELECT snapshot::text FROM $schema.entry_revision",
                        String::class.java,
                    )
                val current = config.target(MigrationVersion.LATEST).load()
                assertThat(current.migrate().migrationsExecuted).isEqualTo(1)
                assertThat(
                    jdbc.queryForObject(
                        "SELECT (to_jsonb(l) - 'history_sequence')::text FROM $schema.ledger_entry l",
                        String::class.java,
                    ),
                ).isEqualTo(ledgerBefore)
                assertThat(
                    jdbc.queryForObject("SELECT snapshot::text FROM $schema.entry_revision", String::class.java),
                ).isEqualTo(auditBefore)
                assertThat(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM $schema.telegram_inbox WHERE message_sent_at IS NULL",
                        Long::class.java,
                    ),
                ).isEqualTo(2)
                assertThat(
                    jdbc.queryForObject("SELECT text FROM $schema.telegram_outbox", String::class.java),
                ).isEqualTo("Сохранённый ответ")
                assertThat(
                    jdbc.queryForObject("SELECT next_offset FROM $schema.telegram_polling_state", String::class.java),
                ).isEqualTo("3")
                assertThat(
                    jdbc.queryForObject("SELECT opening_balance::text FROM $schema.account", String::class.java),
                ).isEqualTo("12.34")
                assertThat(
                    jdbc.queryForObject("SELECT history_sequence FROM $schema.ledger_entry", Long::class.java),
                ).isPositive()
                assertThat(current.migrate().migrationsExecuted).isZero()
            } finally {
                jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
            }
        }
    }
