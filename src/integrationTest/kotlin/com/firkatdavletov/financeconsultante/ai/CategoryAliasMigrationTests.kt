package com.firkatdavletov.financeconsultante.ai

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

class CategoryAliasMigrationTests
    @Autowired
    constructor(
        private val dataSource: DataSource,
    ) : PostgresIntegrationTest() {
        @Test
        @Suppress("LongMethod") // The V14 fixture proves both rule preservation and draft defaults.
        fun `V15 preserves exact rules and adds phrase aliases to AI drafts`() {
            val schema = "category_alias_${UUID.randomUUID().toString().replace("-", "")}"
            val jdbc = JdbcTemplate(dataSource)
            try {
                val configuration =
                    Flyway
                        .configure()
                        .dataSource(dataSource)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .locations("classpath:db/migration")
                configuration.target(MigrationVersion.fromVersion("14")).load().migrate()
                val household = UUID.randomUUID()
                val member = UUID.randomUUID()
                val account = UUID.randomUUID()
                val category = UUID.randomUUID()
                val entry = UUID.randomUUID()
                val draft = UUID.randomUUID()
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
                    "INSERT INTO $schema.category(id, household_id, name, kind) VALUES (?, ?, 'Транспорт', 'EXPENSE')",
                    category,
                    household,
                )
                jdbc.update(
                    """
                    INSERT INTO $schema.ledger_entry(
                        id, household_id, account_id, accounting_started_at, category_id, direction,
                        original_amount, original_currency, amount_kzt, occurred_at, source_key, actor_id, created_at
                    ) VALUES (?, ?, ?, '2026-09-01T00:00:00Z', ?, 'EXPENSE', 500, 'KZT', 500,
                        '2026-09-05T18:00:00Z', 'migration:alias:1', ?, '2026-09-05T19:00:00Z')
                    """.trimIndent(),
                    entry,
                    household,
                    account,
                    category,
                    member,
                )
                jdbc.update(
                    "INSERT INTO $schema.entry_revision(household_id, entry_id, version, actor_id, action, snapshot, " +
                        "created_at) VALUES (?, ?, 0, ?, 'CREATED', '{}'::jsonb, '2026-09-05T19:00:00Z')",
                    household,
                    entry,
                    member,
                )
                jdbc.update(
                    "INSERT INTO $schema.category_rule(household_id, keyword, category_id, corrected_entry_id, " +
                        "corrected_version) VALUES (?, 'автобус', ?, ?, 0)",
                    household,
                    category,
                    entry,
                )
                jdbc.update("INSERT INTO $schema.telegram_polling_state(bot_id) VALUES (123456)")
                jdbc.update(
                    "INSERT INTO $schema.telegram_inbox(bot_id, update_id, owner_id, member_id, text, status) " +
                        "VALUES (123456, 1, 555, ?, 'Автобус 500', 'PROCESSED')",
                    member,
                )
                jdbc.update(
                    "INSERT INTO $schema.transaction_draft(id, household_id, member_id, owner_id, bot_id, " +
                        "source_inbox_id, state, amount, currency, category_key, occurred_on, reason, category_name, " +
                        "category_direction, category_id) VALUES (?, ?, ?, 555, 123456, 1, 'READY', 500, 'KZT', " +
                        "?, '2026-09-06', 'Подтвердите', 'Транспорт', 'EXPENSE', ?)",
                    draft,
                    household,
                    member,
                    category.toString(),
                    category,
                )

                val current = configuration.target(MigrationVersion.fromVersion("15")).load()
                assertThat(current.migrate().migrationsExecuted).isEqualTo(1)
                assertThat(
                    jdbc.queryForObject(
                        "SELECT keyword FROM $schema.category_rule WHERE household_id = ?",
                        String::class.java,
                        household,
                    ),
                ).isEqualTo("автобус")
                assertThat(
                    jdbc.queryForMap(
                        "SELECT recognized_by_ai, category_alias FROM $schema.transaction_draft WHERE id = ?",
                        draft,
                    ),
                ).containsEntry("recognized_by_ai", false).containsEntry("category_alias", null)

                jdbc.update(
                    "UPDATE $schema.category_rule SET keyword = 'маршрутный автобус' WHERE household_id = ?",
                    household,
                )
                jdbc.update(
                    "UPDATE $schema.transaction_draft SET recognized_by_ai = TRUE, " +
                        "category_alias = 'маршрутный автобус' WHERE id = ?",
                    draft,
                )
                assertThatThrownBy {
                    jdbc.update("UPDATE $schema.transaction_draft SET category_alias = 'Автобус' WHERE id = ?", draft)
                }.isInstanceOf(RuntimeException::class.java)
                assertThat(current.migrate().migrationsExecuted).isZero()
            } finally {
                jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
            }
        }
    }
