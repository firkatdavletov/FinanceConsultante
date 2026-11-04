package com.firkatdavletov.financeconsultante.recurring

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

class OneTimeRuleMigrationTests
    @Autowired
    constructor(
        private val dataSource: DataSource,
    ) : PostgresIntegrationTest() {
        @Test
        @Suppress("LongMethod", "MagicNumber") // Preserve existing data and check schedule boundaries.
        fun `V32 preserves existing rules and validates one-time and repeating schedules`() {
            val jdbc = JdbcTemplate(dataSource)
            listOf(true, false).forEach { upgrade ->
                val schema = "once_rules_${UUID.randomUUID().toString().replace("-", "")}"
                try {
                    val configuration =
                        Flyway
                            .configure()
                            .dataSource(dataSource)
                            .schemas(schema)
                            .defaultSchema(schema)
                            .locations("classpath:db/migration")
                    configuration
                        .target(if (upgrade) MigrationVersion.fromVersion("31") else MigrationVersion.fromVersion("32"))
                        .load()
                        .migrate()
                    val household = UUID.randomUUID()
                    val member = UUID.randomUUID()
                    val category = UUID.randomUUID()
                    jdbc.update("INSERT INTO $schema.household(id) VALUES (?)", household)
                    jdbc.update(
                        "INSERT INTO $schema.member(id,household_id,telegram_user_id) VALUES (?,?,123)",
                        member,
                        household,
                    )
                    jdbc.update(
                        "INSERT INTO $schema.category(id,household_id,name,kind) VALUES (?,?,?,'EXPENSE')",
                        category,
                        household,
                        "Synthetic expense",
                    )
                    val rule = UUID.randomUUID()
                    jdbc.update(
                        """INSERT INTO $schema.recurring_rule(id,household_id,member_id,category_id,
                        frequency,schedule_value,amount,starts_on,created_at,updated_at)
                        VALUES (?,?,?,?,'MONTHLY',31,100,'2026-09-01',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)""",
                        rule,
                        household,
                        member,
                        category,
                    )
                    val before =
                        jdbc.queryForObject(
                            "SELECT to_jsonb(r)::text FROM $schema.recurring_rule r",
                            String::class.java,
                        )
                    configuration.target(MigrationVersion.fromVersion("32")).load().migrate()
                    assertThat(
                        jdbc.queryForObject(
                            "SELECT to_jsonb(r)::text FROM $schema.recurring_rule r",
                            String::class.java,
                        ),
                    ).isEqualTo(before)
                    listOf("ONCE" to null, "MONTHLY" to 1, "MONTHLY" to 31, "WEEKLY" to 1, "WEEKLY" to 7)
                        .forEach { (frequency, schedule) ->
                            jdbc.update(
                                "UPDATE $schema.recurring_rule SET frequency=?,schedule_value=? WHERE id=?",
                                frequency,
                                schedule,
                                rule,
                            )
                        }
                    listOf(
                        "ONCE" to 1,
                        "MONTHLY" to null,
                        "MONTHLY" to 0,
                        "MONTHLY" to 32,
                        "WEEKLY" to null,
                        "WEEKLY" to 0,
                        "WEEKLY" to 8,
                        "UNKNOWN" to null,
                    ).forEach { (frequency, schedule) ->
                        assertThatThrownBy {
                            jdbc.update(
                                "UPDATE $schema.recurring_rule SET frequency=?,schedule_value=? WHERE id=?",
                                frequency,
                                schedule,
                                rule,
                            )
                        }.isInstanceOf(DataIntegrityViolationException::class.java)
                    }
                } finally {
                    jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
                }
            }
        }
    }
