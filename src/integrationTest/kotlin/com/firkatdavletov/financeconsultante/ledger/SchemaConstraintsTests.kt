package com.firkatdavletov.financeconsultante.ledger

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.ledger.application.AccountingService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import java.math.BigDecimal
import java.util.UUID

class SchemaConstraintsTests
    @Autowired
    constructor(
        private val service: AccountingService,
        private val jdbc: JdbcClient,
    ) : PostgresIntegrationTest() {
        @Test
        fun `tests use PostgreSQL major version 17`() {
            assertThat(jdbc.sql("SHOW server_version_num").query(String::class.java).single()).startsWith("17")
        }

        @ParameterizedTest
        @ValueSource(strings = ["0", "-1", "1.001", "1.000", "100000000000000000", "NaN", "Infinity", "-Infinity"])
        fun `database rejects invalid amounts without rounding`(amount: String) {
            val setup = AccountingFixtures.budget()
            service.openBudget(setup)
            val entry = service.record(AccountingFixtures.command(setup)).entry
            assertThatThrownBy {
                jdbc
                    .sql(
                        """
                        UPDATE ledger_entry SET original_amount = CAST(? AS numeric), amount_kzt = CAST(? AS numeric)
                        WHERE id = ?
                        """.trimIndent(),
                    ).params(amount, amount, entry.id)
                    .update()
            }.isInstanceOf(DataIntegrityViolationException::class.java)
            assertThat(
                jdbc
                    .sql("SELECT original_amount FROM ledger_entry WHERE id = ?")
                    .param(entry.id)
                    .query(BigDecimal::class.java)
                    .single(),
            ).isEqualByComparingTo("100")
        }

        @ParameterizedTest
        @ValueSource(strings = ["USD", "RUB", "kzt"])
        fun `database rejects unsupported currency and RUB without a conversion`(currency: String) {
            val setup = AccountingFixtures.budget()
            service.openBudget(setup)
            val entry = service.record(AccountingFixtures.command(setup)).entry
            assertThatThrownBy {
                jdbc
                    .sql(
                        "UPDATE ledger_entry SET original_currency = ? WHERE id = ?",
                    ).params(currency, entry.id)
                    .update()
            }.isInstanceOf(DataIntegrityViolationException::class.java)
        }

        @ParameterizedTest
        @ValueSource(strings = ["0", "-1", "1.000000001", "100000000000", "NaN", "Infinity"])
        fun `database rejects invalid daily exchange rate`(rate: String) {
            assertThatThrownBy {
                jdbc
                    .sql(
                        """
                        INSERT INTO daily_exchange_rate (rate_date, rub_kzt, source, fetched_at)
                        VALUES ('2026-05-01', CAST(? AS numeric), 'TEST', CURRENT_TIMESTAMP)
                        """.trimIndent(),
                    ).param(rate)
                    .update()
            }.isInstanceOf(DataIntegrityViolationException::class.java)
        }

        @Test
        fun `composite foreign keys reject account category and actor from another household`() {
            val setup = AccountingFixtures.budget()
            val other = AccountingFixtures.budget()
            service.openBudget(setup)
            service.openBudget(other)
            val entry = service.record(AccountingFixtures.command(setup)).entry
            mapOf(
                "account_id" to other.account.id,
                "category_id" to other.categories.last().id,
                "actor_id" to other.owner.id,
            ).forEach { (column, foreignId) ->
                assertThatThrownBy {
                    jdbc.sql("UPDATE ledger_entry SET $column = ? WHERE id = ?").params(foreignId, entry.id).update()
                }.isInstanceOf(DataIntegrityViolationException::class.java)
            }
            assertThatThrownBy {
                jdbc.sql("UPDATE ledger_entry SET direction = 'INCOME' WHERE id = ?").param(entry.id).update()
            }.isInstanceOf(DataIntegrityViolationException::class.java)
        }

        @Test
        fun `database rejects entries before accounting and mismatched base valuation`() {
            val setup = AccountingFixtures.budget()
            service.openBudget(setup)
            val entry = service.record(AccountingFixtures.command(setup)).entry
            listOf(
                "occurred_at = accounting_started_at - INTERVAL '1 microsecond'",
                "occurred_at = created_at + INTERVAL '1 second'",
                "accounting_started_at = accounting_started_at - INTERVAL '1 day'",
                "amount_kzt = 101",
                "status = 'DRAFT'",
                "version = -1",
            ).forEach { assignment ->
                assertThatThrownBy {
                    jdbc.sql("UPDATE ledger_entry SET $assignment WHERE id = ?").param(entry.id).update()
                }.isInstanceOf(DataIntegrityViolationException::class.java)
            }
        }

        @Test
        fun `failed budget creation rolls back all setup tables`() {
            val first = AccountingFixtures.budget()
            service.openBudget(first)
            val second = AccountingFixtures.budget()
            val duplicateOwner = second.copy(owner = second.owner.copy(telegramUserId = first.owner.telegramUserId))
            assertThatThrownBy {
                service.openBudget(
                    duplicateOwner,
                )
            }.isInstanceOf(DataIntegrityViolationException::class.java)
            assertThat(
                jdbc
                    .sql("SELECT id FROM household WHERE id = ?")
                    .param(second.household.id)
                    .query(UUID::class.java)
                    .optional(),
            ).isEmpty()
        }
    }
