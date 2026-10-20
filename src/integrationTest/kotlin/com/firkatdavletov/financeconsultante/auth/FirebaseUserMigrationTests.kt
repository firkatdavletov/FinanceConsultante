package com.firkatdavletov.financeconsultante.auth

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import javax.sql.DataSource

@Suppress("MagicNumber")
class FirebaseUserMigrationTests
    @Autowired
    constructor(
        private val dataSource: DataSource,
        private val jdbc: JdbcTemplate,
    ) : PostgresIntegrationTest() {
        @Test fun `V23 preserves legacy UUIDs members account and token binding`() {
            val schema = "firebase_migration_${UUID.randomUUID().toString().replace("-", "")}"
            val household = UUID.randomUUID()
            val member = UUID.randomUUID()
            val account = UUID.randomUUID()
            try {
                Flyway
                    .configure()
                    .dataSource(
                        dataSource,
                    ).schemas(schema)
                    .defaultSchema(schema)
                    .target("22")
                    .load()
                    .migrate()
                jdbc.update("INSERT INTO $schema.household(id) VALUES (?)", household)
                jdbc.update(
                    "INSERT INTO $schema.member(id, household_id, role) VALUES (?, ?, 'OWNER')",
                    member,
                    household,
                )
                jdbc.update(
                    "INSERT INTO $schema.account(id, household_id, opening_balance, accounting_started_at) " +
                        "VALUES (?, ?, 123.45, '2026-09-01T00:00:00Z')",
                    account,
                    household,
                )
                jdbc.update(
                    "INSERT INTO $schema.mobile_budget_access(token_hash, household_id, member_id) VALUES (?, ?, ?)",
                    "a".repeat(64),
                    household,
                    member,
                )
                val before = jdbc.queryForMap("SELECT * FROM $schema.account WHERE id = ?", account)
                val flyway =
                    Flyway
                        .configure()
                        .dataSource(dataSource)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .load()
                assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1)
                flyway.validate()
                assertThat(jdbc.queryForMap("SELECT * FROM $schema.account WHERE id = ?", account)).isEqualTo(before)
                assertThat(jdbc.queryForObject("SELECT member_id FROM $schema.mobile_budget_access", UUID::class.java))
                    .isEqualTo(member)
                assertThat(jdbc.queryForObject("SELECT count(*) FROM $schema.firebase_user", Long::class.java)).isZero()
            } finally {
                jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
            }
        }
    }
