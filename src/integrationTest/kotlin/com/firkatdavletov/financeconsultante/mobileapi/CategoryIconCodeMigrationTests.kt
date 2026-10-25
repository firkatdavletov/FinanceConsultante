package com.firkatdavletov.financeconsultante.mobileapi

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

class CategoryIconCodeMigrationTests
    @Autowired
    constructor(
        private val dataSource: DataSource,
    ) : PostgresIntegrationTest() {
        @Test
        @Suppress("LongMethod") // Check both upgrade and fresh schema using isolated synthetic categories.
        fun `V28 preserves old categories and allows opaque codes in fresh and upgraded databases`() {
            val jdbc = JdbcTemplate(dataSource)
            listOf(true, false).forEach { upgrade ->
                val schema = "icon_codes_${UUID.randomUUID().toString().replace("-", "")}"
                try {
                    val configuration =
                        Flyway
                            .configure()
                            .dataSource(dataSource)
                            .schemas(schema)
                            .defaultSchema(schema)
                            .locations("classpath:db/migration")
                    val household = UUID.randomUUID()
                    val category = UUID.randomUUID()
                    if (upgrade) {
                        configuration.target(MigrationVersion.fromVersion("27")).load().migrate()
                        jdbc.update("INSERT INTO $schema.household(id) VALUES (?)", household)
                        jdbc.update(
                            "INSERT INTO $schema.category(id, household_id, name, kind, icon_code) " +
                                "VALUES (?, ?, ?, ?, ?)",
                            category,
                            household,
                            "Продукты",
                            "EXPENSE",
                            "FOOD",
                        )
                    }
                    configuration.target(MigrationVersion.LATEST).load().migrate()
                    if (upgrade) {
                        assertThat(
                            jdbc.queryForObject(
                                "SELECT icon_code FROM $schema.category WHERE id = ?",
                                String::class.java,
                                category,
                            ),
                        ).isEqualTo("FOOD")
                    } else {
                        jdbc.update("INSERT INTO $schema.household(id) VALUES (?)", household)
                        jdbc.update(
                            "INSERT INTO $schema.category(id, household_id, name, kind) VALUES (?, ?, ?, ?)",
                            category,
                            household,
                            "Продукты",
                            "EXPENSE",
                        )
                    }
                    val code = " custom.icon-v2 "
                    jdbc.update("UPDATE $schema.category SET icon_code = ? WHERE id = ?", code, category)
                    assertThat(
                        jdbc.queryForObject(
                            "SELECT icon_code FROM $schema.category WHERE id = ?",
                            String::class.java,
                            category,
                        ),
                    ).isEqualTo(code)
                    assertThatThrownBy {
                        jdbc.update("UPDATE $schema.category SET icon_code = NULL WHERE id = ?", category)
                    }.isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)
                } finally {
                    jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
                }
            }
        }
    }
