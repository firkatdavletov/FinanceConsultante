package com.firkatdavletov.financeconsultante.telegram

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

class ManualDraftCategoryMigrationTests
    @Autowired
    constructor(
        private val dataSource: DataSource,
    ) : PostgresIntegrationTest() {
        @Test
        @Suppress("LongMethod") // The V16 fixture proves draft preservation and both new persistent states.
        fun `V17 preserves drafts and adds manual category input states`() {
            val schema = "manual_category_${UUID.randomUUID().toString().replace("-", "")}"
            val jdbc = JdbcTemplate(dataSource)
            try {
                val configuration =
                    Flyway
                        .configure()
                        .dataSource(dataSource)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .locations("classpath:db/migration")
                configuration.target(MigrationVersion.fromVersion("16")).load().migrate()
                val household = UUID.randomUUID()
                val member = UUID.randomUUID()
                val account = UUID.randomUUID()
                val category = UUID.randomUUID()
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
                    "INSERT INTO $schema.category(id, household_id, name, kind) VALUES (?, ?, 'Другое', 'EXPENSE')",
                    category,
                    household,
                )
                jdbc.update("INSERT INTO $schema.telegram_polling_state(bot_id) VALUES (123456)")
                jdbc.update(
                    "INSERT INTO $schema.telegram_inbox(bot_id, update_id, owner_id, member_id, text, status) " +
                        "VALUES (123456, 1, 555, ?, NULL, 'PROCESSED')",
                    member,
                )
                jdbc.update(
                    "INSERT INTO $schema.transaction_draft(id, household_id, member_id, owner_id, bot_id, " +
                        "source_inbox_id, state, amount, currency, category_key, occurred_on, reason, category_name, " +
                        "category_direction, category_id, recognized_by_ai, category_alias) VALUES " +
                        "(?, ?, ?, 555, 123456, 1, 'READY', 9656, 'KZT', ?, '2026-09-20', 'Подтвердите', " +
                        "'Другое', 'EXPENSE', ?, TRUE, 'стиральный порошок')",
                    draft,
                    household,
                    member,
                    category.toString(),
                    category,
                )

                val current = configuration.target(MigrationVersion.fromVersion("17")).load()
                assertThat(current.migrate().migrationsExecuted).isEqualTo(1)
                assertThat(
                    jdbc.queryForMap(
                        "SELECT state, amount::text, category_alias FROM $schema.transaction_draft WHERE id = ?",
                        draft,
                    ),
                ).containsEntry("state", "READY")
                    .containsEntry("amount", "9656")
                    .containsEntry("category_alias", "стиральный порошок")

                jdbc.update(
                    "UPDATE $schema.transaction_draft SET state = 'NEEDS_CATEGORY', recognized_by_ai = FALSE " +
                        "WHERE id = ?",
                    draft,
                )
                jdbc.update(
                    "UPDATE $schema.transaction_draft SET state = 'NEEDS_CATEGORY_NAME', version = version + 1 " +
                        "WHERE id = ?",
                    draft,
                )
                assertThat(
                    jdbc.queryForMap(
                        "SELECT state, recognized_by_ai FROM $schema.transaction_draft WHERE id = ?",
                        draft,
                    ),
                ).containsEntry("state", "NEEDS_CATEGORY_NAME").containsEntry("recognized_by_ai", false)
                assertThatThrownBy {
                    jdbc.update("UPDATE $schema.transaction_draft SET state = 'UNKNOWN' WHERE id = ?", draft)
                }.isInstanceOf(RuntimeException::class.java)
                assertThat(current.migrate().migrationsExecuted).isZero()
            } finally {
                jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
            }
        }
    }
