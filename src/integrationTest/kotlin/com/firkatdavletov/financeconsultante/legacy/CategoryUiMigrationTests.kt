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

class CategoryUiMigrationTests
    @Autowired
    constructor(
        private val dataSource: DataSource,
    ) : PostgresIntegrationTest() {
        @Test
        @Suppress("LongMethod") // The migration fixture verifies all compatibility defaults in one schema lifecycle.
        fun `V14 preserves categories and outbox while adding icons dialogs and edit delivery`() {
            val schema = "category_ui_${UUID.randomUUID().toString().replace("-", "")}"
            val jdbc = JdbcTemplate(dataSource)
            try {
                val configuration =
                    Flyway
                        .configure()
                        .dataSource(dataSource)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .locations("classpath:db/migration")
                configuration.target(MigrationVersion.fromVersion("13")).load().migrate()
                val household = UUID.randomUUID()
                val groceries = UUID.randomUUID()
                val custom = UUID.randomUUID()
                jdbc.update("INSERT INTO $schema.household(id) VALUES (?)", household)
                jdbc.update(
                    "INSERT INTO $schema.category(id, household_id, name, kind, dictionary_key) " +
                        "VALUES (?, ?, 'Продукты', 'EXPENSE', 'GROCERIES'), (?, ?, 'Поездки', 'EXPENSE', NULL)",
                    groceries,
                    household,
                    custom,
                    household,
                )
                jdbc.update("INSERT INTO $schema.telegram_polling_state(bot_id) VALUES (123456)")
                jdbc.update(
                    "INSERT INTO $schema.telegram_inbox(bot_id, update_id, owner_id, text, status) " +
                        "VALUES (123456, 1, 555, '/categories', 'PROCESSED')",
                )
                jdbc.update(
                    "INSERT INTO $schema.telegram_outbox(inbox_id, bot_id, owner_id, text) " +
                        "VALUES (1, 123456, 555, 'Старый ответ')",
                )

                val current = configuration.target(MigrationVersion.fromVersion("14")).load()
                assertThat(current.migrate().migrationsExecuted).isEqualTo(1)
                assertThat(
                    jdbc.queryForObject(
                        "SELECT icon_code FROM $schema.category WHERE id = ?",
                        String::class.java,
                        groceries,
                    ),
                ).isEqualTo("FOOD")
                assertThat(
                    jdbc.queryForObject(
                        "SELECT icon_code FROM $schema.category WHERE id = ?",
                        String::class.java,
                        custom,
                    ),
                ).isEqualTo("OTHER")
                assertThat(
                    jdbc.queryForMap("SELECT text, delivery_method, target_message_id FROM $schema.telegram_outbox"),
                ).containsEntry("text", "Старый ответ").containsEntry("delivery_method", "SEND")

                jdbc.update(
                    "INSERT INTO $schema.category(id, household_id, name, kind, dictionary_key) " +
                        "VALUES (?, ?, 'Другое', 'EXPENSE', 'OTHER')",
                    UUID.randomUUID(),
                    household,
                )
                jdbc.update(
                    "INSERT INTO $schema.telegram_category_dialog(owner_id, step, category_id, screen_message_id) " +
                        "VALUES (555, 'RENAME', ?, 73)",
                    custom,
                )
                jdbc.update(
                    "UPDATE $schema.telegram_outbox SET delivery_method = 'EDIT', target_message_id = 73",
                )
                assertThatThrownBy {
                    jdbc.update(
                        "UPDATE $schema.telegram_outbox SET target_message_id = NULL WHERE delivery_method = 'EDIT'",
                    )
                }.isInstanceOf(RuntimeException::class.java)
                assertThat(current.migrate().migrationsExecuted).isZero()
            } finally {
                jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
            }
        }
    }
