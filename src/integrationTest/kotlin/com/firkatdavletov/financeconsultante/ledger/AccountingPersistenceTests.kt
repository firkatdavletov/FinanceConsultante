package com.firkatdavletov.financeconsultante.ledger

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.ledger.application.AccountingService
import com.firkatdavletov.financeconsultante.ledger.application.ExchangeRateRepository
import com.firkatdavletov.financeconsultante.ledger.application.LedgerRepository
import com.firkatdavletov.financeconsultante.ledger.application.MissingExchangeRate
import com.firkatdavletov.financeconsultante.ledger.domain.DailyExchangeRate
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AccountingPersistenceTests
    @Autowired
    constructor(
        private val service: AccountingService,
        private val ledger: LedgerRepository,
        private val rates: ExchangeRateRepository,
        private val jdbc: JdbcClient,
        private val flyway: Flyway,
        private val transactions: PlatformTransactionManager,
    ) : PostgresIntegrationTest() {
        @Test
        fun `fresh migration and repeated startup preserve ledger and audit`() {
            val setup = AccountingFixtures.budget("100")
            service.openBudget(setup)
            val result = service.record(AccountingFixtures.command(setup))
            assertThat(flyway.info().applied().map { it.version.version }).containsExactly("1", "2")
            assertThat(flyway.migrate().migrationsExecuted).isZero()
            flyway.validate()
            assertThat(ledger.findBySource(setup.household.id, result.entry.sourceKey)).isEqualTo(result.entry)
            assertThat(count("entry_revision", setup.household.id)).isEqualTo(1)
            val defaults =
                jdbc
                    .sql("SELECT base_currency, time_zone, report_time FROM household WHERE id = ?")
                    .param(setup.household.id)
                    .query()
                    .singleRow()
            assertThat(defaults["base_currency"]).isEqualTo("KZT")
            assertThat(defaults["time_zone"]).isEqualTo("Asia/Almaty")
            assertThat(defaults["report_time"].toString()).isEqualTo("08:00:00")
        }

        @Test
        fun `posted income expenses refunds and opening balance give expected totals`() {
            val setup = AccountingFixtures.budget()
            service.openBudget(setup)
            service.record(AccountingFixtures.command(setup, "1300000", Direction.INCOME))
            listOf("15000", "700", "1300").forEach { service.record(AccountingFixtures.command(setup, it)) }
            val balance = service.balance(setup.household.id, setup.owner.id, AccountingFixtures.cutoff)
            assertThat(balance.amount).isEqualTo(Money.of("1283000"))
            assertThat(balance.income).isEqualTo(Money.of("1300000"))
            assertThat(balance.netExpenses).isEqualTo(Money.of("17000"))
            service.record(AccountingFixtures.command(setup, "300", Direction.EXPENSE_REFUND))
            assertThat(service.balance(setup.household.id, setup.owner.id, AccountingFixtures.cutoff).netExpenses)
                .isEqualTo(Money.of("16700"))
        }

        @Test
        fun `duplicate source records only one operation and audit revision and keeps debt`() {
            val setup = AccountingFixtures.budget("10")
            service.openBudget(setup)
            val command = AccountingFixtures.command(setup)
            val first = service.record(command)
            val repeated = service.record(command)
            assertThat(first.recorded).isTrue()
            assertThat(repeated.recorded).isFalse()
            assertThat(repeated.entry).isEqualTo(first.entry)
            assertThat(count("ledger_entry", setup.household.id)).isEqualTo(1)
            assertThat(count("entry_revision", setup.household.id)).isEqualTo(1)
            val balance = service.balance(setup.household.id, setup.owner.id, AccountingFixtures.cutoff)
            assertThat(balance.amount).isEqualTo(Money.of("-90"))
            assertThat(balance.income).isEqualTo(Money.of("0"))
        }

        @Test
        fun `RUB conversion uses Almaty date and persists original amount and rate`() {
            val setup = AccountingFixtures.budget()
            service.openBudget(setup)
            val rate =
                DailyExchangeRate(LocalDate.parse("2026-03-01"), BigDecimal("5.125"), "TEST", AccountingFixtures.start)
            rates.fix(rate)
            val posted = service.record(AccountingFixtures.command(setup, "100", currency = Currency.RUB))
            val stored = ledger.findBySource(setup.household.id, posted.entry.sourceKey)
            assertThat(stored).isEqualTo(posted.entry)
            assertThat(stored?.originalAmount).isEqualTo(Money.of("100", Currency.RUB))
            assertThat(stored?.amountKzt).isEqualTo(Money.of("512.50"))
            assertThat(stored?.exchangeRate?.date).isEqualTo(LocalDate.parse("2026-03-01"))
            rates.fix(rate.copy(date = rate.date.plusDays(1), rubKzt = BigDecimal("6")))
            listOf(
                "occurred_at = occurred_at + INTERVAL '2 days'",
                "rub_kzt = 6, amount_kzt = 600",
                "amount_kzt = 512.51",
            ).forEach { assignment ->
                assertThatThrownBy {
                    jdbc.sql("UPDATE ledger_entry SET $assignment WHERE id = ?").param(posted.entry.id).update()
                }.isInstanceOf(DataIntegrityViolationException::class.java)
            }
            assertThatThrownBy {
                jdbc.sql("UPDATE daily_exchange_rate SET rub_kzt = 7 WHERE rate_date = ?").param(rate.date).update()
            }.isInstanceOf(DataIntegrityViolationException::class.java)
            assertThat(service.balance(setup.household.id, setup.owner.id, AccountingFixtures.cutoff).amount)
                .isEqualTo(Money.of("-512.50"))
        }

        @Test
        fun `daily rate fixation is idempotent and conflicting overwrite is rejected`() {
            val date = LocalDate.parse("2026-04-01")
            val rate = DailyExchangeRate(date, BigDecimal("5"), "TEST", AccountingFixtures.start)
            assertThat(rates.fix(rate)).isEqualTo(rate)
            assertThat(rates.fix(rate.copy(fetchedAt = rate.fetchedAt.plusSeconds(1)))).isEqualTo(rate)
            assertThatIllegalArgumentException().isThrownBy { rates.fix(rate.copy(rubKzt = BigDecimal("6"))) }
            assertThat(rates.find(date)).isEqualTo(rate)
        }

        @Test
        fun `missing rate and out of range dates fail without writes`() {
            val setup = AccountingFixtures.budget()
            service.openBudget(setup)
            val command =
                AccountingFixtures
                    .command(setup, currency = Currency.RUB)
                    .copy(occurredAt = AccountingFixtures.start.plusSeconds(86400 * 10))
            assertThatThrownBy { service.record(command) }.isInstanceOf(MissingExchangeRate::class.java)
            assertThatIllegalArgumentException().isThrownBy {
                service.record(command.copy(occurredAt = Instant.parse("2026-09-06T03:00:01Z")))
            }
            assertThatIllegalArgumentException().isThrownBy {
                service.record(
                    AccountingFixtures
                        .command(
                            setup,
                        ).copy(occurredAt = setup.account.accountingStartedAt.minusSeconds(1)),
                )
            }
            assertThatIllegalArgumentException().isThrownBy {
                service.record(AccountingFixtures.command(setup).copy(amount = Money.of("0")))
            }
            assertThat(count("ledger_entry", setup.household.id)).isZero()
        }

        @Test
        fun `foreign member and category cannot read or write a budget`() {
            val setup = AccountingFixtures.budget()
            val other = AccountingFixtures.budget()
            service.openBudget(setup)
            service.openBudget(other)
            assertThatIllegalArgumentException().isThrownBy {
                service.balance(setup.household.id, other.owner.id, AccountingFixtures.cutoff)
            }
            assertThatIllegalArgumentException().isThrownBy {
                service.record(AccountingFixtures.command(setup).copy(memberId = other.owner.id))
            }
            assertThatIllegalArgumentException().isThrownBy {
                service.record(AccountingFixtures.command(setup).copy(categoryId = other.categories.first().id))
            }
            assertThat(count("ledger_entry", setup.household.id)).isZero()
        }

        @Test
        fun `audit failure rolls back the financial operation`() {
            val setup = AccountingFixtures.budget()
            service.openBudget(setup)
            val command = AccountingFixtures.command(setup).copy(sourceKey = "test:reject-audit")
            jdbc
                .sql(
                    """
                    ALTER TABLE entry_revision ADD CONSTRAINT test_reject_audit
                    CHECK (snapshot->>'source_key' <> 'test:reject-audit') NOT VALID
                    """.trimIndent(),
                ).update()
            try {
                assertThatThrownBy { service.record(command) }.isInstanceOf(DataIntegrityViolationException::class.java)
                assertThat(count("ledger_entry", setup.household.id)).isZero()
                assertThat(count("entry_revision", setup.household.id)).isZero()
            } finally {
                jdbc.sql("ALTER TABLE entry_revision DROP CONSTRAINT test_reject_audit").update()
            }
            assertThat(service.record(command).recorded).isTrue()
        }

        @Test
        fun `two transactions racing on the same source produce one entry and audit`() {
            val setup = AccountingFixtures.budget()
            service.openBudget(setup)
            val original = service.record(AccountingFixtures.command(setup)).entry
            val source = UUID.randomUUID().toString()
            val ready = CountDownLatch(2)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val futures =
                    (1..2).map {
                        executor.submit<Boolean> {
                            TransactionTemplate(transactions).execute {
                                assertThat(ledger.findBySource(setup.household.id, source)).isNull()
                                ready.countDown()
                                check(ready.await(10, TimeUnit.SECONDS))
                                ledger.insert(original.copy(id = UUID.randomUUID(), sourceKey = source))
                            }
                        }
                    }
                assertThat(futures.map { it.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS) })
                    .containsExactlyInAnyOrder(true, false)
            } finally {
                executor.shutdownNow()
            }
            assertThat(count("ledger_entry", setup.household.id)).isEqualTo(2)
            assertThat(count("entry_revision", setup.household.id)).isEqualTo(2)
        }

        private fun count(
            table: String,
            household: UUID,
        ): Long =
            jdbc
                .sql("SELECT count(*) FROM $table WHERE household_id = ?")
                .param(household)
                .query(Long::class.java)
                .single()

        companion object {
            private const val RACE_TIMEOUT_SECONDS = 20L
        }
    }
