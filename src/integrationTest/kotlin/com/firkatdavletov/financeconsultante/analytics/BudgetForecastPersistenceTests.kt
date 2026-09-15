package com.firkatdavletov.financeconsultante.analytics

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.analytics.application.BudgetForecastService
import com.firkatdavletov.financeconsultante.analytics.domain.GoalReachability
import com.firkatdavletov.financeconsultante.analytics.domain.SpendingEstimateSource
import com.firkatdavletov.financeconsultante.goals.application.GoalChange
import com.firkatdavletov.financeconsultante.goals.application.SavingsGoalService
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
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

@Suppress("MagicNumber", "LongParameterList", "LongMethod") // Financial fixtures stay together as one scenario.
class BudgetForecastPersistenceTests
    @Autowired
    constructor(
        private val accounting: AccountingService,
        private val recurring: RecurringPlanService,
        private val goals: SavingsGoalService,
        private val forecasts: BudgetForecastService,
        private val jdbc: JdbcTemplate,
    ) : PostgresIntegrationTest() {
        private lateinit var setup: BudgetSetup
        private lateinit var owner: OwnedLedger

        @BeforeEach
        fun createBudget() {
            cleanupOwner()
            val household = Household(UUID.randomUUID(), reportTime = LocalTime.of(8, 0))
            val start = LocalDate.parse("2026-08-01").atStartOfDay(ZONE).toInstant()
            setup =
                BudgetSetup(
                    household,
                    Member(UUID.randomUUID(), household.id, OWNER),
                    Account(UUID.randomUUID(), household.id, Money.of("2000"), start),
                    listOf(
                        Category(UUID.randomUUID(), household.id, "Продукты", CategoryKind.EXPENSE),
                        Category(UUID.randomUUID(), household.id, "Жильё", CategoryKind.EXPENSE),
                        Category(UUID.randomUUID(), household.id, "Зарплата", CategoryKind.INCOME),
                    ),
                )
            accounting.openBudget(setup)
            owner = OwnedLedger(household.id, setup.owner.id, OWNER, start)
        }

        @AfterEach
        fun cleanup() {
            cleanupOwner()
        }

        @Test
        fun `forecast SQL excludes linked expenses and fulfilled plans without changing ledger`() {
            record(Direction.EXPENSE, "900", expense("Продукты"), "ordinary", "2026-08-10")
            val oldRule =
                applied(
                    recurring.createRule(
                        owner,
                        RecurrenceFrequency.MONTHLY,
                        20,
                        Money.of("500"),
                        expense("Жильё"),
                        LocalDate.parse("2026-08-01"),
                    ),
                )
            val oldOccurrence =
                recurring.overview(owner, TODAY).occurrences.single {
                    it.occurrence.ruleId == oldRule.rule.id && it.occurrence.dueOn == LocalDate.parse("2026-08-20")
                }
            applied(
                recurring.pay(
                    owner,
                    oldOccurrence.occurrence.id,
                    0,
                    Money.of("500"),
                    LocalDate.parse("2026-08-20"),
                    "synthetic:t13:old-payment",
                ),
            )
            applied(recurring.deactivateRule(owner, oldRule.rule.id, oldRule.rule.version, TODAY))

            val futureRule =
                applied(
                    recurring.createRule(
                        owner,
                        RecurrenceFrequency.MONTHLY,
                        8,
                        Money.of("150"),
                        expense("Жильё"),
                        LocalDate.parse("2026-09-01"),
                    ),
                )
            val futureOccurrence =
                recurring.overview(owner, TODAY).occurrences.single {
                    it.occurrence.ruleId == futureRule.rule.id && it.occurrence.dueOn == LocalDate.parse("2026-09-08")
                }
            applied(
                recurring.pay(
                    owner,
                    futureOccurrence.occurrence.id,
                    0,
                    Money.of("50"),
                    TODAY,
                    "synthetic:t13:partial",
                ),
            )

            recurring.createExpectedIncome(owner, Money.of("400"), LocalDate.parse("2026-09-10"), income(), TODAY)
            val fulfilled =
                applied(recurring.createExpectedIncome(owner, Money.of("200"), TODAY, income(), TODAY))
            applied(
                recurring.receiveExpectedIncome(
                    owner,
                    fulfilled.expectedIncome.id,
                    0,
                    Money.of("200"),
                    TODAY,
                    "synthetic:t13:income",
                ),
            )

            val goal =
                appliedGoal(
                    goals.create(
                        owner,
                        "Отпуск",
                        Money.of("200"),
                        LocalDate.parse("2026-09-20"),
                        1,
                        TODAY,
                        "synthetic:t13:goal",
                    ),
                )
            val reserved =
                appliedGoal(
                    goals.reserve(owner, goal.id, 0, Money.of("100"), "synthetic:t13:reserve"),
                )
            appliedGoal(
                goals.planContribution(
                    owner,
                    reserved.id,
                    1,
                    Money.of("100"),
                    LocalDate.parse("2026-09-09"),
                    TODAY,
                    "synthetic:t13:plan",
                ),
            )
            val ledgerBefore = count("ledger_entry")

            val forecast = forecasts.forecast(owner)

            assertThat(forecast.spending.source).isEqualTo(SpendingEstimateSource.HISTORICAL_AVERAGE)
            assertThat(forecast.spending.baseDaily).isEqualTo(Money.of("30"))
            val incomeDay = forecast.base.days.single { it.expectedIncome.amount.signum() > 0 }
            assertThat(incomeDay.date).isEqualTo(LocalDate.parse("2026-09-10"))
            assertThat(forecast.base.days.sumOf { it.expectedIncome.amount }).isEqualByComparingTo("400.00")
            assertThat(forecast.base.days.sumOf { it.obligations.amount }).isEqualByComparingTo("100.00")
            assertThat(
                forecast.base.goals
                    .single()
                    .reachability,
            ).isEqualTo(GoalReachability.REACHABLE)
            assertThat(forecast.base.endingBalance).isEqualTo(Money.of("150"))
            assertThat(forecast.cautious.endingBalance).isEqualTo(Money.of("-30"))
            assertThat(count("ledger_entry")).isEqualTo(ledgerBefore)
        }

        @Test
        fun `foreign owner cannot read forecast`() {
            assertThatThrownBy { forecasts.forecast(owner.copy(memberId = UUID.randomUUID())) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Budget access denied")
        }

        private fun record(
            direction: Direction,
            amount: String,
            categoryId: UUID,
            source: String,
            date: String,
        ) {
            accounting.record(
                RecordEntry(
                    owner.householdId,
                    owner.memberId,
                    categoryId,
                    direction,
                    Money.of(amount),
                    LocalDate.parse(date).atStartOfDay(ZONE).toInstant(),
                    "synthetic:t13:$source",
                ),
            )
        }

        private fun expense(name: String): UUID = setup.categories.single { it.name == name }.id

        private fun income(): UUID = setup.categories.single { it.kind == CategoryKind.INCOME }.id

        private fun <T> applied(result: PlanChange<T>): T = (result as PlanChange.Applied).value

        private fun <T> appliedGoal(result: GoalChange<T>): T = (result as GoalChange.Applied).value

        private fun count(table: String): Long =
            checkNotNull(
                jdbc.queryForObject(
                    "SELECT count(*) FROM $table WHERE household_id = ?",
                    Long::class.java,
                    owner.householdId,
                ),
            )

        private fun cleanupOwner() {
            val households = jdbc.queryForList("SELECT household_id FROM member WHERE telegram_user_id = ?", OWNER)
            households.forEach { row ->
                val household = row.getValue("household_id")
                val tables =
                    listOf(
                        "savings_goal_allocation",
                        "goal_contribution_plan",
                        "savings_goal",
                        "expected_income_match",
                        "expected_income",
                        "recurring_occurrence_payment",
                        "recurring_occurrence",
                        "recurring_rule",
                        "entry_revision",
                        "ledger_entry",
                        "category",
                        "account",
                        "member",
                    )
                for (table in tables) jdbc.update("DELETE FROM $table WHERE household_id = ?", household)
                jdbc.update("DELETE FROM household WHERE id = ?", household)
            }
        }

        companion object {
            private const val OWNER = 737373L
            private val TODAY = LocalDate.parse("2026-09-06")
            private val ZONE: ZoneId = ZoneId.of("Asia/Almaty")
        }
    }
