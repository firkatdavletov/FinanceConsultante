package com.firkatdavletov.financeconsultante.legacy

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import javax.sql.DataSource

@Suppress("MagicNumber") // Stable synthetic IDs and counts make the migration assertions explicit.
class MultipleTransactionDraftMigrationTests
    @Autowired
    constructor(
        private val dataSource: DataSource,
    ) : PostgresIntegrationTest() {
        @Test
        @Suppress("LongMethod") // The migration check preserves a V9 draft and exercises both new partial indexes.
        fun `V10 preserves an active draft and allows multiple ready drafts but one clarification`() {
            val schema = "multiple_drafts_${UUID.randomUUID().toString().replace("-", "")}"
            val jdbc = JdbcTemplate(dataSource)
            try {
                val config =
                    Flyway
                        .configure()
                        .dataSource(dataSource)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .locations("classpath:db/migration")
                config.target(MigrationVersion.fromVersion("9")).load().migrate()
                val household = UUID.randomUUID()
                val member = UUID.randomUUID()
                jdbc.update("INSERT INTO $schema.household(id) VALUES (?)", household)
                jdbc.update(
                    "INSERT INTO $schema.member(id, household_id, telegram_user_id) VALUES (?, ?, 555)",
                    member,
                    household,
                )
                jdbc.update("INSERT INTO $schema.telegram_polling_state(bot_id) VALUES (123456)")
                repeat(4) { index ->
                    jdbc.update(
                        "INSERT INTO $schema.telegram_inbox(bot_id, update_id, owner_id, status) " +
                            "VALUES (123456, ?, 555, 'PROCESSED')",
                        index + 1,
                    )
                }
                val existing = UUID.randomUUID()
                insertReady(jdbc, schema, existing, household, member, 1)
                val before =
                    jdbc.queryForObject(
                        "SELECT to_jsonb(d)::text FROM $schema.transaction_draft d",
                        String::class.java,
                    )

                val current = config.target(MigrationVersion.fromVersion("10")).load()
                assertThat(current.migrate().migrationsExecuted).isEqualTo(1)
                assertThat(
                    jdbc.queryForObject(
                        "SELECT to_jsonb(d)::text FROM $schema.transaction_draft d WHERE id = ?",
                        String::class.java,
                        existing,
                    ),
                ).isEqualTo(before)

                insertReady(jdbc, schema, UUID.randomUUID(), household, member, 2)
                assertThat(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM $schema.transaction_draft WHERE state = 'READY'",
                        Long::class.java,
                    ),
                ).isEqualTo(2)

                insertClarification(jdbc, schema, UUID.randomUUID(), household, member, 3)
                assertThatThrownBy {
                    insertClarification(jdbc, schema, UUID.randomUUID(), household, member, 4)
                }.isInstanceOf(DataIntegrityViolationException::class.java)
                assertThat(current.migrate().migrationsExecuted).isZero()
            } finally {
                jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
            }
        }

        private fun insertReady(
            jdbc: JdbcTemplate,
            schema: String,
            id: UUID,
            household: UUID,
            member: UUID,
            inbox: Int,
        ) {
            jdbc.update(
                """
                INSERT INTO $schema.transaction_draft(id, household_id, member_id, owner_id, bot_id, source_inbox_id,
                    state, version, amount, currency, category_key, occurred_on, reason,
                    category_name, category_direction)
                VALUES (?, ?, ?, 555, 123456, ?, 'READY', 0, 10, 'KZT', 'GROCERIES', '2026-09-06',
                    'Подтвердите', 'Продукты', 'EXPENSE')
                """.trimIndent(),
                id,
                household,
                member,
                inbox,
            )
        }

        private fun insertClarification(
            jdbc: JdbcTemplate,
            schema: String,
            id: UUID,
            household: UUID,
            member: UUID,
            inbox: Int,
        ) {
            jdbc.update(
                """
                INSERT INTO $schema.transaction_draft(id, household_id, member_id, owner_id, bot_id, source_inbox_id,
                    state, version, reason)
                VALUES (?, ?, ?, 555, 123456, ?, 'NEEDS_INPUT', 0, 'Уточните')
                """.trimIndent(),
                id,
                household,
                member,
                inbox,
            )
        }
    }
