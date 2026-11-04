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

class FullRuleEditingMigrationTests
    @Autowired
    constructor(
        private val dataSource: DataSource,
    ) : PostgresIntegrationTest() {
        @Test
        @Suppress("LongMethod", "MagicNumber") // Upgrade real historical definitions and verify database constraints.
        fun `V33 keeps legacy rules and incomes and enforces income schedule identity`() {
            val jdbc = JdbcTemplate(dataSource)
            val schema = "full_rules_${UUID.randomUUID().toString().replace("-", "")}"
            try {
                val config =
                    Flyway
                        .configure()
                        .dataSource(dataSource)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .locations("classpath:db/migration")
                config.target(MigrationVersion.fromVersion("32")).load().migrate()
                val household = UUID.randomUUID()
                val member = UUID.randomUUID()
                val expense = UUID.randomUUID()
                val income = UUID.randomUUID()
                val rule = UUID.randomUUID()
                val expected = UUID.randomUUID()
                jdbc.update("INSERT INTO $schema.household(id) VALUES (?)", household)
                jdbc.update(
                    "INSERT INTO $schema.member(id,household_id,telegram_user_id) VALUES (?,?,123)",
                    member,
                    household,
                )
                jdbc.update(
                    "INSERT INTO $schema.category(id,household_id,name,kind) VALUES (?,?,'Expense','EXPENSE')",
                    expense,
                    household,
                )
                jdbc.update(
                    "INSERT INTO $schema.category(id,household_id,name,kind) VALUES (?,?,'Income','INCOME')",
                    income,
                    household,
                )
                jdbc.update(
                    """
                    INSERT INTO $schema.recurring_rule(id,household_id,member_id,category_id,frequency,amount,
                        starts_on,created_at,updated_at) VALUES (?,?,?,?,'ONCE',100,'2026-09-01',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """.trimIndent(),
                    rule,
                    household,
                    member,
                    expense,
                )
                jdbc.update(
                    """
                    INSERT INTO $schema.expected_income(id,household_id,member_id,category_id,planned_amount,
                        due_on,created_at,updated_at) VALUES (?,?,?,?,100,'2026-09-01',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """.trimIndent(),
                    expected,
                    household,
                    member,
                    income,
                )
                val oldRule = jdbc.queryForMap("SELECT * FROM $schema.recurring_rule WHERE id=?", rule)
                val oldIncome = jdbc.queryForMap("SELECT * FROM $schema.expected_income WHERE id=?", expected)
                config.target(MigrationVersion.LATEST).load().migrate()
                assertThat(
                    jdbc.queryForMap("SELECT * FROM $schema.recurring_rule WHERE id=?", rule),
                ).containsAllEntriesOf(oldRule)
                assertThat(
                    jdbc.queryForMap("SELECT * FROM $schema.expected_income WHERE id=?", expected),
                ).containsAllEntriesOf(oldIncome)
                listOf("", " ", "x".repeat(161)).forEach { name ->
                    assertThatThrownBy {
                        jdbc.update("UPDATE $schema.recurring_rule SET rule_name=? WHERE id=?", name, rule)
                    }.isInstanceOf(DataIntegrityViolationException::class.java)
                }
                assertThatThrownBy {
                    jdbc.update("UPDATE $schema.expected_income SET rule_id=? WHERE id=?", UUID.randomUUID(), expected)
                }.isInstanceOf(DataIntegrityViolationException::class.java)
                jdbc.update(
                    """
                    INSERT INTO $schema.income_rule(id,household_id,member_id,category_id,amount,due_on,
                        income_name,income_frequency,income_active,effective_on,created_at,updated_at)
                    VALUES (?,?,?,?,100,'2026-09-01','Synthetic','ONCE',TRUE,'2026-09-01',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """.trimIndent(),
                    expected,
                    household,
                    member,
                    income,
                )
                jdbc.update("UPDATE $schema.expected_income SET rule_id=id,scheduled_on=due_on WHERE id=?", expected)
                assertThatThrownBy {
                    jdbc.update(
                        """
                        INSERT INTO $schema.expected_income(id,household_id,member_id,category_id,planned_amount,due_on,
                            rule_id,scheduled_on,created_at,updated_at)
                        VALUES (?,?,?,?,100,'2026-09-01',?,'2026-09-01',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                        """.trimIndent(),
                        UUID.randomUUID(),
                        household,
                        member,
                        income,
                        expected,
                    )
                }.isInstanceOf(DataIntegrityViolationException::class.java)
                assertThatThrownBy {
                    jdbc.update("UPDATE $schema.income_rule SET income_frequency='DAILY' WHERE id=?", expected)
                }.isInstanceOf(DataIntegrityViolationException::class.java)
                assertThatThrownBy {
                    jdbc.update("UPDATE $schema.income_rule SET category_id=? WHERE id=?", expense, expected)
                }.isInstanceOf(DataIntegrityViolationException::class.java)
            } finally {
                jdbc.execute("DROP SCHEMA $schema CASCADE")
            }
        }
    }
