package com.firkatdavletov.financeconsultante.goals

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.budget.application.BudgetReportService
import com.firkatdavletov.financeconsultante.goals.application.GoalChange
import com.firkatdavletov.financeconsultante.goals.application.SavingsGoalService
import com.firkatdavletov.financeconsultante.goals.domain.ContributionPlanStatus
import com.firkatdavletov.financeconsultante.goals.domain.SavingsGoal
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
import com.firkatdavletov.financeconsultante.recurring.application.RecurringPlanService
import com.firkatdavletov.financeconsultante.recurring.domain.RecurrenceFrequency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Suppress("MagicNumber", "TooManyFunctions")
class SavingsGoalPersistenceTests
    @Autowired
    constructor(
        private val service: SavingsGoalService,
        private val accounting: AccountingService,
        private val recurring: RecurringPlanService,
        private val reports: BudgetReportService,
        private val jdbc: JdbcTemplate,
        transactionManager: PlatformTransactionManager,
    ) : PostgresIntegrationTest() {
        private val tx = TransactionTemplate(transactionManager)
        private lateinit var setup: BudgetSetup
        private lateinit var owner: OwnedLedger
        private val today = LocalDate.parse("2026-09-06")

        @BeforeEach
        fun createBudget() {
            val household = Household(UUID.randomUUID())
            val start = Instant.parse("2026-09-01T00:00:00Z")
            setup =
                BudgetSetup(
                    household,
                    Member(UUID.randomUUID(), household.id, positiveTelegramId()),
                    Account(UUID.randomUUID(), household.id, Money.of("300000"), start),
                    listOf(Category(UUID.randomUUID(), household.id, "Покупка", CategoryKind.EXPENSE)),
                )
            accounting.openBudget(setup)
            jdbc.update("UPDATE household SET cash_reserve = 50000 WHERE id = ?", household.id)
            owner = OwnedLedger(household.id, setup.owner.id, setup.owner.telegramUserId, start)
        }

        @AfterEach
        fun removeBudget() {
            if (!::setup.isInitialized) return
            val household = setup.household.id
            jdbc.update("DELETE FROM savings_goal_allocation WHERE household_id = ?", household)
            jdbc.update("DELETE FROM goal_contribution_plan WHERE household_id = ?", household)
            jdbc.update("DELETE FROM savings_goal WHERE household_id = ?", household)
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

        @Test
        fun `goal reserve enters G once and release restores availability without ledger entries`() {
            recurring.createRule(
                owner,
                RecurrenceFrequency.MONTHLY,
                today.dayOfMonth,
                Money.of("100000"),
                expenseCategory(),
                today,
            )
            val goal = createGoal("50000", "synthetic:goal:create")

            val reserved = applied(service.reserve(owner, goal.id, 0, Money.of("30000"), "synthetic:goal:reserve"))
            val repeated = applied(service.reserve(owner, goal.id, 0, Money.of("30000"), "synthetic:goal:reserve"))
            val view = checkNotNull(tx.execute { reports.today(owner) })

            assertThat(reserved.allocatedAmount).isEqualTo(Money.of("30000"))
            assertThat(repeated).isEqualTo(reserved)
            assertThat(view.current.goalReserve).isEqualTo(Money.of("30000"))
            assertThat(view.current.freeAmount).isEqualTo(Money.of("120000"))
            assertThat(count("ledger_entry")).isZero()
            assertThat(count("savings_goal_allocation")).isEqualTo(1)

            val released =
                applied(service.release(owner, goal.id, reserved.version, Money.of("10000"), "synthetic:goal:release"))
            val after = checkNotNull(tx.execute { reports.today(owner) })
            assertThat(released.allocatedAmount).isEqualTo(Money.of("20000"))
            assertThat(after.snapshot.recalculated).isTrue()
            assertThat(after.current.freeAmount).isEqualTo(Money.of("130000"))
            assertThat(count("ledger_entry")).isZero()
        }

        @Test
        fun `concurrent reservations cannot allocate the same free funds to two goals`() {
            jdbc.update("UPDATE household SET cash_reserve = 200000 WHERE id = ?", owner.householdId)
            val first = createGoal("100000", "synthetic:goal:first")
            val second = createGoal("100000", "synthetic:goal:second")
            val executor = Executors.newFixedThreadPool(2)
            try {
                val results =
                    executor
                        .invokeAll(
                            listOf(
                                Callable {
                                    service.reserve(owner, first.id, 0, Money.of("100000"), "synthetic:reserve:first")
                                },
                                Callable {
                                    service.reserve(owner, second.id, 0, Money.of("100000"), "synthetic:reserve:second")
                                },
                            ),
                        ).map { it.get(10, TimeUnit.SECONDS) }

                assertThat(results.count { it is GoalChange.Applied }).isEqualTo(1)
                assertThat(results.count { it is GoalChange.Rejected }).isEqualTo(1)
                assertThat(service.overview(owner, today).totalAllocated).isEqualTo(Money.of("100000"))
                assertThat(count("savings_goal_allocation")).isEqualTo(1)
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `future contribution can be cancelled without changing B or G`() {
            val goal = createGoal("100000", "synthetic:plan:goal")
            val plan =
                applied(
                    service.planContribution(
                        owner,
                        goal.id,
                        goal.version,
                        Money.of("10000"),
                        today.plusDays(5),
                        today,
                        "synthetic:plan:create",
                    ),
                )
            val cancelled =
                applied(service.cancelContribution(owner, plan.id, plan.version, "synthetic:plan:cancel"))

            assertThat(cancelled.status).isEqualTo(ContributionPlanStatus.CANCELLED)
            assertThat(
                accounting.currentBalance(owner.householdId, owner.memberId).amount,
            ).isEqualTo(Money.of("300000"))
            assertThat(service.overview(owner, today).totalAllocated).isEqualTo(Money.of("0"))
            assertThat(count("ledger_entry")).isZero()
        }

        @Test
        fun `goal funded expense reduces B and G atomically and replay does not duplicate it`() {
            val goal = createGoal("50000", "synthetic:spend:goal")
            val reserved = applied(service.reserve(owner, goal.id, 0, Money.of("30000"), "synthetic:spend:reserve"))

            val receipt =
                applied(
                    service.spend(
                        owner,
                        goal.id,
                        reserved.version,
                        Money.of("20000"),
                        expenseCategory(),
                        today,
                        "synthetic:spend:fact",
                    ),
                )
            val repeated =
                applied(
                    service.spend(
                        owner,
                        goal.id,
                        reserved.version,
                        Money.of("20000"),
                        expenseCategory(),
                        today,
                        "synthetic:spend:fact",
                    ),
                )

            assertThat(receipt.goal.allocatedAmount).isEqualTo(Money.of("10000"))
            assertThat(repeated.entry.id).isEqualTo(receipt.entry.id)
            assertThat(repeated.recorded).isFalse()
            assertThat(
                accounting.currentBalance(owner.householdId, owner.memberId).amount,
            ).isEqualTo(Money.of("280000"))
            assertThat(service.overview(owner, today).totalAllocated).isEqualTo(Money.of("10000"))
            assertThat(count("ledger_entry")).isEqualTo(1)
            assertThat(count("savings_goal_allocation")).isEqualTo(2)
        }

        @Test
        fun `ordinary expense can make availability negative while allocated goal money stays reserved`() {
            val goal = createGoal("50000", "synthetic:deficit:goal")
            service.reserve(owner, goal.id, 0, Money.of("30000"), "synthetic:deficit:reserve")
            accounting.record(
                RecordEntry(
                    owner.householdId,
                    owner.memberId,
                    expenseCategory(),
                    Direction.EXPENSE,
                    Money.of("250000"),
                    today.atStartOfDay(ZONE).toInstant(),
                    "synthetic:deficit:expense",
                ),
            )

            val report = checkNotNull(tx.execute { reports.today(owner) })

            assertThat(report.current.goalReserve).isEqualTo(Money.of("30000"))
            assertThat(report.current.freeAmount).isEqualTo(Money.of("-30000"))
            assertThat(report.remainingDailyLimit).isEqualTo(Money.of("0"))
        }

        private fun createGoal(
            target: String,
            source: String,
        ): SavingsGoal =
            applied(
                service.create(
                    owner,
                    "Синтетическая цель",
                    Money.of(target),
                    today.plusMonths(2),
                    2,
                    today,
                    source,
                ),
            )

        private fun expenseCategory(): UUID = setup.categories.single().id

        private fun positiveTelegramId(): Long = UUID.randomUUID().mostSignificantBits ushr 1

        private fun count(table: String): Long =
            checkNotNull(
                jdbc.queryForObject(
                    "SELECT count(*) FROM $table WHERE household_id = ?",
                    Long::class.java,
                    owner.householdId,
                ),
            )

        private fun <T> applied(result: GoalChange<T>): T {
            assertThat(result).isInstanceOf(GoalChange.Applied::class.java)
            return (result as GoalChange.Applied<T>).value
        }

        private companion object {
            val ZONE: java.time.ZoneId = java.time.ZoneId.of("Asia/Almaty")
        }
    }
