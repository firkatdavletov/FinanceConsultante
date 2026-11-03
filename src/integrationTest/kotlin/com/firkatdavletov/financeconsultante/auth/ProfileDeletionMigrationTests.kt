package com.firkatdavletov.financeconsultante.auth

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import javax.sql.DataSource

class ProfileDeletionMigrationTests
    @Autowired
    constructor(
        private val dataSource: DataSource,
        private val jdbc: JdbcTemplate,
    ) : PostgresIntegrationTest() {
        @Test fun `V30 preserves V29 users and financial settings until explicit deletion`() {
            val schema = "profile_migration_${UUID.randomUUID().toString().replace("-", "")}"
            try {
                Flyway
                    .configure()
                    .dataSource(dataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .target("29")
                    .load()
                    .migrate()
                val id = UUID.randomUUID()
                jdbc.update("INSERT INTO $schema.firebase_user(id, firebase_uid) VALUES (?, 'synthetic-migration')", id)
                jdbc.update("INSERT INTO $schema.household(id) VALUES (?)", UUID.randomUUID())
                val before = jdbc.queryForMap("SELECT * FROM $schema.firebase_user")
                val budget = jdbc.queryForMap("SELECT * FROM $schema.household")
                val current =
                    Flyway
                        .configure()
                        .dataSource(dataSource)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .load()
                assertThat(current.migrate().migrationsExecuted).isEqualTo(2)
                current.validate()
                assertThat(jdbc.queryForMap("SELECT * FROM $schema.firebase_user")).isEqualTo(before)
                assertThat(jdbc.queryForMap("SELECT * FROM $schema.household")).isEqualTo(budget)
                assertThat(
                    jdbc.queryForObject("SELECT count(*) FROM $schema.firebase_profile_deletion", Int::class.java),
                ).isZero()
            } finally {
                jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
            }
        }
    }
