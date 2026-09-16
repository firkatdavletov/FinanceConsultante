package com.firkatdavletov.financeconsultante.access

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

class FamilyBudgetMigrationTests
    @Autowired
    constructor(
        private val dataSource: DataSource,
    ) : PostgresIntegrationTest() {
        @Test
        @Suppress("LongMethod")
        fun `V13 preserves owner and queues while enabling bounded family membership`() {
            val schema = "family_${UUID.randomUUID().toString().replace("-", "")}"
            val jdbc = JdbcTemplate(dataSource)
            try {
                val configuration =
                    Flyway
                        .configure()
                        .dataSource(dataSource)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .locations("classpath:db/migration")
                configuration.target(MigrationVersion.fromVersion("12")).load().migrate()
                val household = UUID.randomUUID()
                val owner = UUID.randomUUID()
                val account = UUID.randomUUID()
                jdbc.update("INSERT INTO $schema.household(id) VALUES (?)", household)
                jdbc.update(
                    "INSERT INTO $schema.member(id, household_id, telegram_user_id) VALUES (?, ?, 9001)",
                    owner,
                    household,
                )
                jdbc.update(
                    "INSERT INTO $schema.account(id, household_id, opening_balance, accounting_started_at) " +
                        "VALUES (?, ?, 0, '2026-09-01T00:00:00Z')",
                    account,
                    household,
                )
                jdbc.update("INSERT INTO $schema.telegram_polling_state(bot_id) VALUES (123456)")
                jdbc.update(
                    "INSERT INTO $schema.telegram_inbox(bot_id, update_id, owner_id, text, status) " +
                        "VALUES (123456, 1, 9001, '/balance', 'RECEIVED')",
                )

                val current = configuration.target(MigrationVersion.fromVersion("13")).load()
                assertThat(current.migrate().migrationsExecuted).isEqualTo(1)
                assertThat(
                    jdbc.queryForObject(
                        "SELECT reports_enabled FROM $schema.member WHERE id = ?",
                        Boolean::class.java,
                        owner,
                    ),
                ).isTrue()
                assertThat(
                    jdbc.queryForObject(
                        "SELECT member_id FROM $schema.telegram_inbox WHERE update_id = 1",
                        UUID::class.java,
                    ),
                ).isEqualTo(owner)

                val member = UUID.randomUUID()
                jdbc.update(
                    "INSERT INTO $schema.member(id, household_id, telegram_user_id, role) " +
                        "VALUES (?, ?, 9002, 'MEMBER')",
                    member,
                    household,
                )
                assertThat(
                    jdbc.queryForObject("SELECT count(*) FROM $schema.member", Long::class.java),
                ).isEqualTo(2)
                assertThatThrownBy {
                    jdbc.update(
                        "INSERT INTO $schema.member(id, household_id, telegram_user_id, role) " +
                            "VALUES (?, ?, 9002, 'MEMBER')",
                        UUID.randomUUID(),
                        household,
                    )
                }.isInstanceOf(RuntimeException::class.java)
                jdbc.update("UPDATE $schema.member SET revoked_at = CURRENT_TIMESTAMP WHERE id = ?", member)
                jdbc.update(
                    "INSERT INTO $schema.member(id, household_id, telegram_user_id, role) " +
                        "VALUES (?, ?, 9002, 'MEMBER')",
                    UUID.randomUUID(),
                    household,
                )
                assertThat(current.migrate().migrationsExecuted).isZero()
            } finally {
                jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
            }
        }
    }
