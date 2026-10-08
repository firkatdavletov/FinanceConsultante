package com.firkatdavletov.financeconsultante.mobileapi

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "app.mobile-api.enabled=true",
        "app.mobile-api.token=synthetic-mobile-token-1234567890",
        "app.mobile-api.telegram-user-id=991002",
    ],
)
@Suppress("MagicNumber", "LongMethod") // One HTTP scenario verifies all mutually dependent dashboard indicators.
class DashboardApiTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val accounting: AccountingService,
        private val recurring: RecurringPlanService,
        private val goals: SavingsGoalService,
        private val jdbc: JdbcTemplate,
        private val clock: Clock,
    ) : PostgresIntegrationTest() {
        private val households = mutableListOf<UUID>()

        @AfterEach
        fun cleanup() {
            households.forEach { household ->
                jdbc.update("DELETE FROM savings_goal_allocation WHERE household_id = ?", household)
                jdbc.update("DELETE FROM goal_contribution_plan WHERE household_id = ?", household)
                jdbc.update("DELETE FROM savings_goal WHERE household_id = ?", household)
                jdbc.update("DELETE FROM expected_income_match WHERE household_id = ?", household)
                jdbc.update("DELETE FROM expected_income WHERE household_id = ?", household)
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

        @Test
        fun `returns a consistent household dashboard and rejects revoked access`() {
            mockMvc
                .perform(get("/api/v1/dashboard"))
                .andExpect(status().isUnauthorized)
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"mobile-api\""))

            val today = LocalDate.now(clock.withZone(API_ZONE))
            val setup = openBudget(today.minusMonths(1).withDayOfMonth(1))
            addMember(setup, API_USER_ID)
            val owner = OwnedLedger(setup.household.id, setup.owner.id, OWNER_ID, setup.account.accountingStartedAt)
            jdbc.update("UPDATE household SET cash_reserve = 50000 WHERE id = ?", setup.household.id)

            record(setup, setup.incomeCategory(), Direction.INCOME, "100000", today.withDayOfMonth(1), "income")
            record(setup, setup.groceryCategory(), Direction.EXPENSE, "30000", today.minusDays(4), "groceries")
            record(setup, setup.transportCategory(), Direction.EXPENSE, "10000", today.minusDays(3), "transport")

            val rule =
                recurring.createRule(
                    owner,
                    RecurrenceFrequency.MONTHLY,
                    today.dayOfMonth,
                    Money.of("100000"),
                    setup.housingCategory().id,
                    today,
                )
            check(rule is PlanChange.Applied)
            val occurrence =
                recurring
                    .overview(owner, today)
                    .occurrences
                    .single { it.occurrence.ruleId == rule.value.rule.id && it.occurrence.dueOn == today }
            check(
                recurring.pay(
                    owner,
                    occurrence.occurrence.id,
                    occurrence.occurrence.version,
                    Money.of("20000"),
                    today,
                    "dashboard:obligation-payment",
                ) is PlanChange.Applied,
            )
            check(
                recurring.createExpectedIncome(
                    owner,
                    Money.of("50000"),
                    today.plusDays(4),
                    setup.incomeCategory().id,
                    today,
                ) is PlanChange.Applied,
            )

            val goal =
                goals.create(
                    owner,
                    "Резервная цель",
                    Money.of("100000"),
                    today.plusMonths(2),
                    1,
                    today,
                    "dashboard:goal",
                )
            check(goal is GoalChange.Applied)
            check(
                goals.reserve(
                    owner,
                    goal.value.id,
                    goal.value.version,
                    Money.of("30000"),
                    "dashboard:goal-reserve",
                ) is GoalChange.Applied,
            )

            mockMvc
                .perform(get("/api/v1/dashboard").header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isOk)
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.date").value(today.toString()))
                .andExpect(jsonPath("$.availableMoney.amount").value("340000.00"))
                .andExpect(jsonPath("$.freeMoney.amount.amount").value("180000.00"))
                .andExpect(jsonPath("$.freeMoney.remainingObligations.amount").value("80000.00"))
                .andExpect(jsonPath("$.freeMoney.protectedReserve.amount").value("50000.00"))
                .andExpect(jsonPath("$.freeMoney.goalReserve.amount").value("30000.00"))
                .andExpect(jsonPath("$.freeMoney.reservedTotal.amount").value("160000.00"))
                .andExpect(jsonPath("$.freeMoney.safeDailyLimit.amount").value("7200.00"))
                .andExpect(jsonPath("$.monthActuals.income.amount").value("100000.00"))
                .andExpect(jsonPath("$.monthActuals.expenses.amount").value("60000.00"))
                .andExpect(jsonPath("$.cashFlow.net.amount").value("40000.00"))
                .andExpect(jsonPath("$.budgetExecution.basis").value("FORECAST"))
                .andExpect(jsonPath("$.budgetExecution.total.spent.amount").value("60000.00"))
                .andExpect(jsonPath("$.budgetExecution.variable.spent.amount").value("40000.00"))
                .andExpect(jsonPath("$.budgetExecution.variable.paceStatus").value("AHEAD_OF_PLAN"))
                .andExpect(jsonPath("$.forecast.estimateSource").value("HISTORICAL_AVERAGE"))
                .andExpect(jsonPath("$.forecast.base.expectedIncome.amount").value("50000.00"))
                .andExpect(jsonPath("$.forecast.base.expectedObligations.amount").value("80000.00"))
                .andExpect(jsonPath("$.forecast.base.expectedVariableExpenses.amount").value("33333.25"))
                .andExpect(jsonPath("$.forecast.base.endingBalance.amount").value("276666.75"))
                .andExpect(jsonPath("$.expenseStructure.shareBasisAmount.amount").value("60000.00"))
                .andExpect(jsonPath("$.expenseStructure.categories[0].name").value("Продукты"))
                .andExpect(jsonPath("$.expenseStructure.categories[0].sharePercent").value("50.0"))
                .andExpect(jsonPath("$.upcomingPayments[0].remainingAmount.amount").value("80000.00"))

            jdbc.update(
                "UPDATE member SET revoked_at = CURRENT_TIMESTAMP WHERE household_id = ? AND telegram_user_id = ?",
                setup.household.id,
                API_USER_ID,
            )
            mockMvc
                .perform(get("/api/v1/dashboard").header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("access_denied"))
        }

        private fun openBudget(accountingStart: LocalDate): BudgetSetup {
            val household = Household(UUID.randomUUID())
            val setup =
                BudgetSetup(
                    household,
                    Member(UUID.randomUUID(), household.id, OWNER_ID),
                    Account(
                        UUID.randomUUID(),
                        household.id,
                        Money.of("300000"),
                        accountingStart.atStartOfDay(API_ZONE).toInstant(),
                    ),
                    listOf(
                        Category(UUID.randomUUID(), household.id, "Доход", CategoryKind.INCOME),
                        Category(UUID.randomUUID(), household.id, "Жильё", CategoryKind.EXPENSE),
                        Category(UUID.randomUUID(), household.id, "Продукты", CategoryKind.EXPENSE),
                        Category(UUID.randomUUID(), household.id, "Транспорт", CategoryKind.EXPENSE),
                    ),
                )
            accounting.openBudget(setup)
            households += household.id
            return setup
        }

        private fun addMember(
            setup: BudgetSetup,
            telegramUserId: Long,
        ) {
            jdbc.update(
                "INSERT INTO member(id, household_id, telegram_user_id, role, joined_at) " +
                    "VALUES (?, ?, ?, 'MEMBER', CURRENT_TIMESTAMP)",
                UUID.randomUUID(),
                setup.household.id,
                telegramUserId,
            )
        }

        private fun record(
            setup: BudgetSetup,
            category: Category,
            direction: Direction,
            amount: String,
            occurredOn: LocalDate,
            source: String,
        ) {
            accounting.record(
                RecordEntry(
                    setup.household.id,
                    setup.owner.id,
                    category.id,
                    direction,
                    Money.of(amount),
                    occurredOn.atStartOfDay(API_ZONE).toInstant(),
                    "dashboard:$source",
                ),
            )
        }

        private fun BudgetSetup.incomeCategory() = categories.single { it.name == "Доход" }

        private fun BudgetSetup.housingCategory() = categories.single { it.name == "Жильё" }

        private fun BudgetSetup.groceryCategory() = categories.single { it.name == "Продукты" }

        private fun BudgetSetup.transportCategory() = categories.single { it.name == "Транспорт" }

        companion object {
            private const val API_USER_ID = 991002L
            private const val OWNER_ID = 991001L
            private const val AUTHORIZATION = "Bearer synthetic-mobile-token-1234567890"
            private val API_ZONE = ZoneId.of("Asia/Almaty")
        }
    }
