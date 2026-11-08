package com.firkatdavletov.financeconsultante.mobileapi

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import javax.sql.DataSource

class InternalDeviceAccessMigrationTests
    @Autowired
    constructor(
        private val dataSource: DataSource,
        private val jdbc: JdbcTemplate,
    ) : PostgresIntegrationTest() {
        @Test
        fun `V35 creates empty installation list and preserves existing user`() {
            val schema = "device_migration_${UUID.randomUUID().toString().replace("-", "")}"
            try {
                val config =
                    Flyway
                        .configure()
                        .dataSource(dataSource)
                        .schemas(schema)
                        .defaultSchema(schema)
                config.target("34").load().migrate()
                val userId = UUID.randomUUID()
                jdbc.update(
                    "INSERT INTO $schema.firebase_user(id, firebase_uid) VALUES (?, ?)",
                    userId,
                    "synthetic-v35",
                )
                val current =
                    Flyway
                        .configure()
                        .dataSource(dataSource)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .target("35")
                        .load()
                assertThat(current.migrate().migrationsExecuted).isEqualTo(1)
                assertThat(
                    jdbc.queryForObject("SELECT count(*) FROM $schema.internal_app_installation", Int::class.java),
                ).isZero()
                assertThat(
                    jdbc.queryForObject(
                        "SELECT id FROM $schema.firebase_user WHERE firebase_uid = ?",
                        UUID::class.java,
                        "synthetic-v35",
                    ),
                ).isEqualTo(userId)
            } finally {
                jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
            }
        }
    }
