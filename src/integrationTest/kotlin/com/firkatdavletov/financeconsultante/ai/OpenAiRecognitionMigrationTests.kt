package com.firkatdavletov.financeconsultante.ai

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import javax.sql.DataSource

class OpenAiRecognitionMigrationTests
    @Autowired
    constructor(
        private val dataSource: DataSource,
    ) : PostgresIntegrationTest() {
        @Test
        @Suppress("LongMethod", "MagicNumber") // Migration verification prepares a complete V5 relation graph.
        fun `V6 preserves V5 Telegram data and adds a constrained recognition queue`() {
            val schema = "openai_migration_${UUID.randomUUID().toString().replace("-", "")}"
            val jdbc = JdbcTemplate(dataSource)
            try {
                val configuration =
                    Flyway
                        .configure()
                        .dataSource(dataSource)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .locations("classpath:db/migration")
                configuration.target(MigrationVersion.fromVersion("5")).load().migrate()
                val household = UUID.randomUUID()
                val member = UUID.randomUUID()
                jdbc.update("INSERT INTO $schema.household(id) VALUES (?)", household)
                jdbc.update(
                    "INSERT INTO $schema.member(id, household_id, telegram_user_id) VALUES (?, ?, 555)",
                    member,
                    household,
                )
                jdbc.update("INSERT INTO $schema.telegram_polling_state(bot_id, next_offset) VALUES (123456, 3)")
                jdbc.update(
                    "INSERT INTO $schema.telegram_inbox(bot_id, update_id, owner_id, text, status) " +
                        "VALUES (123456, 1, 555, 'Синтетическая операция 10', 'RECEIVED')",
                )
                jdbc.update(
                    "INSERT INTO $schema.telegram_inbox(bot_id, update_id, owner_id, status) " +
                        "VALUES (123456, 2, 555, 'PROCESSED')",
                )
                jdbc.update(
                    "INSERT INTO $schema.telegram_outbox(inbox_id, bot_id, owner_id, text) " +
                        "VALUES (2, 123456, 555, 'Сохранённый ответ')",
                )

                val current = configuration.target(MigrationVersion.fromVersion("6")).load()
                assertThat(current.migrate().migrationsExecuted).isEqualTo(1)
                assertThat(
                    jdbc.queryForObject("SELECT count(*) FROM $schema.telegram_inbox", Long::class.java),
                ).isEqualTo(2)
                assertThat(
                    jdbc.queryForObject("SELECT text FROM $schema.telegram_outbox", String::class.java),
                ).isEqualTo("Сохранённый ответ")
                assertThat(
                    jdbc.queryForObject("SELECT next_offset FROM $schema.telegram_polling_state", Long::class.java),
                ).isEqualTo(3)
                jdbc.update("UPDATE $schema.telegram_inbox SET status = 'OPENAI_PENDING' WHERE id = 1")
                jdbc.update(
                    """
                    INSERT INTO $schema.openai_recognition_job(
                        id, inbox_id, bot_id, owner_id, household_id, member_id,
                        prompt_version, schema_version, model_id
                    ) VALUES (?, 1, 123456, 555, ?, ?, 'transaction-recognition-v1',
                        'transaction-recognition-v1', 'gpt-4o-mini-2024-07-18')
                    """.trimIndent(),
                    UUID.randomUUID(),
                    household,
                    member,
                )
                assertThat(
                    jdbc.queryForObject("SELECT status FROM $schema.openai_recognition_job", String::class.java),
                ).isEqualTo("PENDING")
                assertThat(current.migrate().migrationsExecuted).isZero()
            } finally {
                jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
            }
        }
    }
