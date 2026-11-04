package com.firkatdavletov.financeconsultante.budget

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.budget.application.BudgetMessages
import com.firkatdavletov.financeconsultante.budget.application.BudgetReportService
import com.firkatdavletov.financeconsultante.ledger.application.AccountingService
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.ledger.application.RecordEntry
import com.firkatdavletov.financeconsultante.ledger.domain.Account
import com.firkatdavletov.financeconsultante.ledger.domain.BudgetSetup
import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.ledger.domain.Household
import com.firkatdavletov.financeconsultante.ledger.domain.Member
import com.firkatdavletov.financeconsultante.recurring.application.PlanChange
import com.firkatdavletov.financeconsultante.recurring.application.RecurringPlanService
import com.firkatdavletov.financeconsultante.recurring.domain.RecurrenceFrequency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Suppress("MagicNumber", "TooManyFunctions")
class BudgetReportPersistenceTests
    @Autowired
    constructor(
        private val accounting: AccountingService,
        private val recurring: RecurringPlanService,
        private val reports: BudgetReportService,
        private val jdbc: JdbcTemplate,
        transactionManager: PlatformTransactionManager,
    ) : PostgresIntegrationTest() {
        private val tx = TransactionTemplate(transactionManager)
        private lateinit var setup: BudgetSetup
        private lateinit var owner: OwnedLedger
        private val date = LocalDate.parse("2026-09-06")

        @BeforeEach
        fun createBudget() {
            cleanupOwner()
            val household = Household(UUID.randomUUID(), reportTime = LocalTime.of(8, 0))
            val start = date.minusDays(5).atStartOfDay(ZONE).toInstant()
            setup =
                BudgetSetup(
                    household,
                    Member(UUID.randomUUID(), household.id, OWNER),
                    Account(UUID.randomUUID(), household.id, Money.of("300000"), start),
                    listOf(
                        Category(UUID.randomUUID(), household.id, "Связь", CategoryKind.EXPENSE),
                        Category(UUID.randomUUID(), household.id, "Зарплата", CategoryKind.INCOME),
                    ),
                )
            accounting.openBudget(setup)
            jdbc.update("UPDATE household SET cash_reserve = 50000 WHERE id = ?", household.id)
            owner = OwnedLedger(household.id, setup.owner.id, OWNER, start)
        }

        @AfterEach
        fun cleanup() {
            cleanupOwner()
        }

        @Test
        @Suppress("LongMethod") // One timeline proves B/R payment offset, daily spend, income, and deficit states.
        fun `payment keeps free amount while ordinary spending reduces remaining daily limit`() {
            val occurrence =
                tx.execute {
                    recurring.createRule(
                        owner,
                        RecurrenceFrequency.MONTHLY,
                        date.dayOfMonth,
                        Money.of("100000"),
                        expenseCategory(),
                        date,
                    )
                    recurring
                        .overview(owner, date)
                        .occurrences
                        .single { it.occurrence.dueOn == date }
                        .occurrence
                }

            val morning = today()
            assertThat(morning.snapshot.balance).isEqualTo(Money.of("300000"))
            assertThat(morning.snapshot.remainingObligations).isEqualTo(Money.of("100000"))
            assertThat(morning.snapshot.freeAmount).isEqualTo(Money.of("150000"))
            assertThat(morning.snapshot.originalDailyLimit).isEqualTo(Money.of("6000"))

            tx.executeWithoutResult {
                assertThat(
                    recurring.pay(
                        owner,
                        occurrence.id,
                        occurrence.version,
                        Money.of("20000"),
                        date,
                        "synthetic:t09:payment",
                    ),
                ).isInstanceOf(PlanChange.Applied::class.java)
            }
            val paid = today()
            assertThat(paid.snapshot.asOf).isEqualTo(morning.snapshot.asOf)
            assertThat(paid.currentFreeAmount).isEqualTo(Money.of("150000"))
            assertThat(paid.remainingDailyLimit).isEqualTo(Money.of("6000"))
            assertThat(BudgetMessages.daily(paid)).contains(
                "Баланс: 280000.00 KZT",
                "Обязательные расходы до конца месяца: 80000.00 KZT",
            )

            record(Direction.EXPENSE, Money.of("10000"), expenseCategory(), "synthetic:t09:ordinary", date)
            val spent = today()
            assertThat(spent.snapshot.asOf).isEqualTo(morning.snapshot.asOf)
            assertThat(spent.spentAfterSnapshot).isEqualTo(Money.of("10000"))
            assertThat(spent.currentFreeAmount).isEqualTo(Money.of("140000"))
            assertThat(spent.remainingDailyLimit).isEqualTo(Money.of("0"))

            record(Direction.INCOME, Money.of("20000"), incomeCategory(), "synthetic:t09:income", date)
            val recalculated = today()
            assertThat(recalculated.snapshot.recalculated).isTrue()
            assertThat(recalculated.snapshot.balance).isEqualTo(Money.of("290000"))
            assertThat(recalculated.snapshot.remainingObligations).isEqualTo(Money.of("80000"))
            assertThat(recalculated.snapshot.freeAmount).isEqualTo(Money.of("160000"))
            assertThat(recalculated.remainingDailyLimit).isEqualTo(Money.of("6400"))
            assertThat(BudgetMessages.daily(recalculated)).contains("Снимок пересчитан", "ожидаемые доходы")

            jdbc.update(
                "UPDATE household SET cash_reserve = 300000, settings_version = settings_version + 1 WHERE id = ?",
                owner.householdId,
            )
            val deficit = today()
            assertThat(deficit.snapshot.freeAmount).isEqualTo(Money.of("-90000"))
            assertThat(deficit.remainingDailyLimit).isEqualTo(Money.of("0"))
            assertThat(BudgetMessages.daily(deficit)).contains("Оценка: дефицит 90000.00 KZT", "лимит 0.00 KZT")
        }

        @Test
        fun `late entry recalculates snapshot and is not deducted twice`() {
            val initial = today()
            record(
                Direction.EXPENSE,
                Money.of("1000"),
                expenseCategory(),
                "synthetic:t09:late",
                date.minusDays(1),
            )

            val recalculated = today()

            assertThat(recalculated.snapshot.recalculated).isTrue()
            assertThat(recalculated.snapshot.balance).isEqualTo(initial.snapshot.balance - Money.of("1000"))
            assertThat(recalculated.spentAfterSnapshot).isEqualTo(Money.of("0"))
        }

        @Test
        fun `daily spending summary excludes obligation payments and averages thirty completed days`() {
            val occurrence =
                tx.execute {
                    recurring.createRule(
                        owner,
                        RecurrenceFrequency.MONTHLY,
                        date.dayOfMonth,
                        Money.of("4000"),
                        expenseCategory(),
                        date,
                    )
                    recurring
                        .overview(owner, date)
                        .occurrences
                        .single { it.occurrence.dueOn == date }
                        .occurrence
                }
            tx.executeWithoutResult {
                assertThat(
                    recurring.pay(
                        owner,
                        occurrence.id,
                        occurrence.version,
                        Money.of("4000"),
                        date.minusDays(1),
                        "synthetic:daily-summary:obligation",
                    ),
                ).isInstanceOf(PlanChange.Applied::class.java)
            }
            record(
                Direction.EXPENSE,
                Money.of("15000"),
                expenseCategory(),
                "synthetic:daily-summary:yesterday",
                date.minusDays(1),
            )
            record(
                Direction.EXPENSE,
                Money.of("30000"),
                expenseCategory(),
                "synthetic:daily-summary:earlier",
                date.minusDays(2),
            )

            val view = today()

            assertThat(view.spendingSummary.yesterday).isEqualTo(Money.of("15000"))
            assertThat(view.spendingSummary.lastThirtyDaysAverage).isEqualTo(Money.of("1500"))
            assertThat(BudgetMessages.daily(view, morning = true)).contains(
                "Доброе утро! Бюджет на 6 сентября:",
                "Вчерашний дневной расход (за исключением обязательных): 15000.00 KZT ⬆️",
                "Средний ежедневный расход за последние 30 дней: 1500.00 KZT ⬇️",
            )
        }

        private fun today() = checkNotNull(tx.execute { reports.today(owner) })

        private fun record(
            direction: Direction,
            amount: Money,
            categoryId: UUID,
            source: String,
            occurredOn: LocalDate,
        ) {
            accounting.record(
                RecordEntry(
                    owner.householdId,
                    owner.memberId,
                    categoryId,
                    direction,
                    amount,
                    occurredOn.atStartOfDay(ZONE).toInstant(),
                    source,
                ),
            )
        }

        private fun expenseCategory(): UUID = setup.categories.single { it.kind == CategoryKind.EXPENSE }.id

        private fun incomeCategory(): UUID = setup.categories.single { it.kind == CategoryKind.INCOME }.id

        private fun cleanupOwner() {
            val households = jdbc.queryForList("SELECT household_id FROM member WHERE telegram_user_id = ?", OWNER)
            households.forEach { row ->
                val household = row.getValue("household_id")
                jdbc.update("DELETE FROM daily_report_job WHERE household_id = ?", household)
                jdbc.update("DELETE FROM daily_budget_snapshot WHERE household_id = ?", household)
                jdbc.update("DELETE FROM recurring_occurrence_payment WHERE household_id = ?", household)
                jdbc.update("DELETE FROM recurring_occurrence WHERE household_id = ?", household)
                jdbc.update("DELETE FROM recurring_rule WHERE household_id = ?", household)
                jdbc.update("DELETE FROM entry_revision WHERE household_id = ?", household)
                jdbc.update("DELETE FROM ledger_entry WHERE household_id = ?", household)
                jdbc.update("DELETE FROM category WHERE household_id = ?", household)
                jdbc.update("DELETE FROM account WHERE household_id = ?", household)
                jdbc.update("DELETE FROM member WHERE household_id = ?", household)
                jdbc.update("DELETE FROM household WHERE id = ?", household)
            }
        }

        companion object {
            private const val OWNER = 919191L
            private val ZONE = java.time.ZoneId.of("Asia/Almaty")
        }
    }
