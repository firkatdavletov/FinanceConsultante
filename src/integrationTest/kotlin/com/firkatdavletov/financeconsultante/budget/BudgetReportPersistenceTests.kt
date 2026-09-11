package com.firkatdavletov.financeconsultante.budget

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.budget.application.BudgetMessages
import com.firkatdavletov.financeconsultante.budget.application.BudgetReportService
import com.firkatdavletov.financeconsultante.budget.application.DailyReportCoordinator
import com.firkatdavletov.financeconsultante.budget.application.DailyReportJobRepository
import com.firkatdavletov.financeconsultante.budget.application.DailyReportWorker
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
import com.firkatdavletov.financeconsultante.telegram.application.TelegramFailure
import com.firkatdavletov.financeconsultante.telegram.infrastructure.JdbcOutboxStore
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
        private val coordinator: DailyReportCoordinator,
        private val jobs: DailyReportJobRepository,
        private val jdbc: JdbcTemplate,
        jdbcClient: JdbcClient,
        transactionManager: PlatformTransactionManager,
    ) : PostgresIntegrationTest() {
        private val tx = TransactionTemplate(transactionManager)
        private val outbox = JdbcOutboxStore(jdbcClient, Duration.ofMinutes(2))
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
                "Учётный баланс: 280000.00 KZT",
                "Обязательства до конца месяца и просроченные: 80000.00 KZT",
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
        fun `concurrent ticks create one durable report and retry the same outbox text after a crash`() {
            val worker = DailyReportWorker(BOT, OWNER, coordinator, Duration.ofMinutes(2), Duration.ofSeconds(1))
            val executor = Executors.newFixedThreadPool(2)
            try {
                val results =
                    executor
                        .invokeAll(listOf(Callable { worker.runOnce() }, Callable { worker.runOnce() }))
                        .map { it.get(10, TimeUnit.SECONDS) }
                assertThat(results.count { it }).isEqualTo(1)
            } finally {
                executor.shutdownNow()
            }
            assertThat(count("daily_report_job")).isEqualTo(1)
            assertThat(reportStatus()).isEqualTo("QUEUED")
            assertThat(count("telegram_outbox")).isEqualTo(1)
            assertThat(
                jdbc.queryForObject(
                    "SELECT scheduled_for FROM daily_report_job WHERE household_id = ?",
                    java.time.OffsetDateTime::class.java,
                    owner.householdId,
                )?.toInstant(),
            ).isEqualTo(Instant.parse("2026-09-06T03:00:00Z"))

            val first = checkNotNull(outbox.claim(BOT))
            assertThat(first.text).contains("Доброе утро!", "Дневной лимит")
            jdbc.update(
                "UPDATE telegram_outbox SET lease_until = clock_timestamp() - interval '1 second' WHERE id = ?",
                first.id,
            )
            val afterCrash = checkNotNull(outbox.claim(BOT))
            assertThat(afterCrash.reportJobId).isEqualTo(first.reportJobId)
            assertThat(afterCrash.text).isEqualTo(first.text)
            outbox.sent(afterCrash, 91)
            assertThat(reportStatus()).isEqualTo("SENT")

            assertThat(worker.runOnce()).isFalse()
            assertThat(count("daily_report_job")).isEqualTo(1)
            assertThat(count("daily_budget_snapshot")).isEqualTo(1)
        }

        @Test
        fun `temporary delivery failure keeps report queued while permanent failure is visible`() {
            DailyReportWorker(BOT, OWNER, coordinator, Duration.ofMinutes(2), Duration.ofSeconds(1)).runOnce()
            val first = checkNotNull(outbox.claim(BOT))
            outbox.failed(first, TelegramFailure("timeout", Duration.ofSeconds(1)))
            assertThat(reportStatus()).isEqualTo("QUEUED")
            jdbc.update("UPDATE telegram_outbox SET next_attempt_at = clock_timestamp() - interval '1 second'")
            val second = checkNotNull(outbox.claim(BOT))
            outbox.failed(second, TelegramFailure("blocked", permanent = true))
            assertThat(reportStatus()).isEqualTo("FAILED")
        }

        @Test
        fun `failure before outbox commit leaves a retryable job without a partial snapshot`() {
            val worker = DailyReportWorker(BOT, OWNER, coordinator, Duration.ofMinutes(2), Duration.ofSeconds(1))
            try {
                jdbc.execute(
                    "ALTER TABLE telegram_outbox ADD CONSTRAINT synthetic_daily_report_failure " +
                        "CHECK (owner_id <> $OWNER OR text = 'blocked')",
                )
                assertThatThrownBy { worker.runOnce() }.isInstanceOf(RuntimeException::class.java)
                assertThat(reportStatus()).isEqualTo("SCHEDULED")
                assertThat(count("telegram_outbox")).isZero()
                assertThat(count("daily_budget_snapshot")).isZero()
            } finally {
                jdbc.execute("ALTER TABLE telegram_outbox DROP CONSTRAINT IF EXISTS synthetic_daily_report_failure")
            }
            jdbc.update(
                "UPDATE daily_report_job SET next_attempt_at = '2026-09-06T03:00:00Z' WHERE household_id = ?",
                owner.householdId,
            )
            assertThat(worker.runOnce()).isTrue()
            assertThat(reportStatus()).isEqualTo("QUEUED")
            assertThat(count("telegram_outbox")).isEqualTo(1)
            assertThat(count("daily_budget_snapshot")).isEqualTo(1)
        }

        @Test
        fun `startup after noon marks one report skipped and enqueues a compact notice`() {
            val lateClock = Clock.fixed(Instant.parse("2026-09-06T07:01:00Z"), ZoneOffset.UTC)
            tx.executeWithoutResult { DailyReportCoordinator(jobs, reports, lateClock).prepare(BOT, OWNER) }
            tx.executeWithoutResult { DailyReportCoordinator(jobs, reports, lateClock).prepare(BOT, OWNER) }

            assertThat(reportStatus()).isEqualTo("SKIPPED")
            assertThat(count("daily_report_job")).isEqualTo(1)
            assertThat(count("telegram_outbox")).isEqualTo(1)
            assertThat(
                jdbc.queryForObject(
                    "SELECT text FROM telegram_outbox WHERE owner_id = ?",
                    String::class.java,
                    OWNER,
                ),
            ).contains("пропущен", "/today")
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

        private fun reportStatus(): String =
            checkNotNull(
                jdbc.queryForObject(
                    "SELECT status FROM daily_report_job WHERE household_id = ?",
                    String::class.java,
                    owner.householdId,
                ),
            )

        private fun count(table: String): Long =
            checkNotNull(
                jdbc.queryForObject(
                    "SELECT count(*) FROM $table WHERE " +
                        if (table == "telegram_outbox") {
                            "owner_id = ?"
                        } else {
                            "household_id = ?"
                        },
                    Long::class.java,
                    if (table == "telegram_outbox") OWNER else owner.householdId,
                ),
            )

        private fun cleanupOwner() {
            jdbc.update("DELETE FROM telegram_outbox WHERE owner_id = ?", OWNER)
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
            jdbc.update("DELETE FROM telegram_polling_state WHERE bot_id = ?", BOT)
        }

        companion object {
            private const val OWNER = 919191L
            private const val BOT = 818181L
            private val ZONE = java.time.ZoneId.of("Asia/Almaty")
        }
    }
