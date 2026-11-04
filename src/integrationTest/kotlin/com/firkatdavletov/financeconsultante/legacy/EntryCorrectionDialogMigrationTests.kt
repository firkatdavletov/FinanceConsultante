package com.firkatdavletov.financeconsultante.legacy

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

class EntryCorrectionDialogMigrationTests
    @Autowired
    constructor(
        private val dataSource: DataSource,
    ) : PostgresIntegrationTest() {
        @Test
        @Suppress("LongMethod", "MagicNumber") // The fixture proves V16 preserves a real V15 ledger entry.
        fun `V16 preserves ledger and adds versioned entry correction dialog`() {
            val schema = "entry_dialog_${UUID.randomUUID().toString().replace("-", "")}"
            val jdbc = JdbcTemplate(dataSource)
            try {
                val configuration =
                    Flyway
                        .configure()
                        .dataSource(dataSource)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .locations("classpath:db/migration")
                configuration.target(MigrationVersion.fromVersion("15")).load().migrate()
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
                        "VALUES (?, ?, 0, '2026-09-01T00:00:00Z')",
                    account,
                    household,
                )
                jdbc.update(
                    "INSERT INTO $schema.category(id, household_id, name, kind) " +
                        "VALUES (?, ?, 'Продукты', 'EXPENSE')",
                    category,
                    household,
                )
                jdbc.update(
                    """INSERT INTO $schema.ledger_entry(
                        id, household_id, account_id, accounting_started_at, category_id, direction,
                        original_amount, original_currency, amount_kzt, occurred_at, source_key,
                        actor_id, created_at
                    ) VALUES (?, ?, ?, '2026-09-01T00:00:00Z', ?, 'EXPENSE', 1300, 'KZT', 1300,
                        '2026-09-02T00:00:00Z', 'synthetic:v15', ?, '2026-09-02T01:00:00Z')""",
                    entry,
                    household,
                    account,
                    category,
                    member,
                )
                val ledgerBefore =
                    jdbc.queryForObject(
                        "SELECT to_jsonb(l)::text FROM $schema.ledger_entry l",
                        String::class.java,
                    )

                val current = configuration.target(MigrationVersion.fromVersion("16")).load()
                assertThat(current.migrate().migrationsExecuted).isEqualTo(1)
                assertThat(
                    jdbc.queryForObject(
                        "SELECT to_jsonb(l)::text FROM $schema.ledger_entry l",
                        String::class.java,
                    ),
                ).isEqualTo(ledgerBefore)

                jdbc.update(
                    "INSERT INTO $schema.telegram_entry_correction_dialog(" +
                        "owner_id, household_id, member_id, entry_id, entry_version) VALUES (555, ?, ?, ?, 0)",
                    household,
                    member,
                    entry,
                )
                assertThat(
                    jdbc.queryForMap("SELECT * FROM $schema.telegram_entry_correction_dialog"),
                ).containsEntry("owner_id", 555L).containsEntry("entry_id", entry).containsEntry("entry_version", 0L)
                assertThatThrownBy {
                    jdbc.update(
                        "UPDATE $schema.telegram_entry_correction_dialog SET entry_version = -1 WHERE owner_id = 555",
                    )
                }.isInstanceOf(RuntimeException::class.java)
                assertThat(current.migrate().migrationsExecuted).isZero()
            } finally {
                jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
            }
        }
    }
