package com.firkatdavletov.financeconsultante.mobileapi

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.ledger.application.AccountingService
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.ledger.domain.Account
import com.firkatdavletov.financeconsultante.ledger.domain.BudgetSetup
import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import com.firkatdavletov.financeconsultante.ledger.domain.Household
import com.firkatdavletov.financeconsultante.ledger.domain.Member
import com.firkatdavletov.financeconsultante.recurring.application.PlanChange
import com.firkatdavletov.financeconsultante.recurring.application.RecurringPlanService
import com.firkatdavletov.financeconsultante.recurring.domain.RecurrenceFrequency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "app.mobile-api.enabled=true",
        "app.mobile-api.token=synthetic-mobile-token-1234567890",
        "app.mobile-api.telegram-user-id=991002",
    ],
)
@Suppress("MagicNumber", "TooManyFunctions") // Synthetic dates and amounts define the HTTP filtering scenarios.
class PlannedOperationApiTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val accounting: AccountingService,
        private val recurring: RecurringPlanService,
        private val jdbc: JdbcTemplate,
    ) : PostgresIntegrationTest() {
        private val households = mutableListOf<UUID>()

        @AfterEach
        fun cleanup() {
            households.forEach { household ->
                listOf(
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
                ).forEach { table -> jdbc.update("DELETE FROM $table WHERE household_id = ?", household) }
                jdbc.update("DELETE FROM household WHERE id = ?", household)
            }
        }

        @Test
        @Suppress("LongMethod") // A mixed response verifies the unified contract and both inclusive month boundaries.
        fun `member reads both types within current month without changing balance`() {
            val setup = openBudget(OWNER_ID)
            addApiMember(setup)
            val owner = owner(setup)
            val expense = createExpense(setup, LocalDate.parse("2026-09-07"))
            check(
                recurring.pay(owner, expense.id, expense.version, Money.of("40.11"), TODAY, "test:planned:partial")
                    is PlanChange.Applied,
            )
            val income = createIncome(setup, TODAY)
            val end = createIncome(setup, LocalDate.parse("2026-09-30"))
            createIncome(setup, TODAY.minusDays(1))
            createIncome(setup, LocalDate.parse("2026-10-01"))
            val foreign = openBudget(991003)
            createIncome(foreign, TODAY)
            createExpense(foreign, TODAY)
            val balance = accounting.currentBalance(owner.householdId, owner.memberId)

            mockMvc
                .perform(request())
                .andExpect(status().isOk)
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.asOf").value("2026-09-06"))
                .andExpect(jsonPath("$.dateFrom").value("2026-09-06"))
                .andExpect(jsonPath("$.dateTo").value("2026-09-30"))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].id").value(income.id.toString()))
                .andExpect(jsonPath("$.items[0].type").value("INCOME"))
                .andExpect(jsonPath("$.items[0].plannedAmount.amount").value("200.00"))
                .andExpect(jsonPath("$.items[0].remainingAmount.amount").value("200.00"))
                .andExpect(jsonPath("$.items[0].paidAmount.amount").value("0.00"))
                .andExpect(jsonPath("$.items[1].id").value(expense.id.toString()))
                .andExpect(jsonPath("$.items[1].type").value("EXPENSE"))
                .andExpect(jsonPath("$.items[1].category.name").value("Тестовый расход"))
                .andExpect(jsonPath("$.items[1].plannedAmount.amount").value("100.00"))
                .andExpect(jsonPath("$.items[1].plannedAmount.currency").value("KZT"))
                .andExpect(jsonPath("$.items[1].paidAmount.amount").value("40.11"))
                .andExpect(jsonPath("$.items[1].remainingAmount.amount").value("59.89"))
                .andExpect(jsonPath("$.items[1].rule.id").value(expense.ruleId.toString()))
                .andExpect(jsonPath("$.items[1].status").value("SCHEDULED"))
                .andExpect(jsonPath("$.items[1].overdue").value(false))
                .andExpect(jsonPath("$.items[1].version").value(1))
                .andExpect(jsonPath("$.items[1].createdAt").isString)
                .andExpect(jsonPath("$.items[1].modifiedAt").isString)
                .andExpect(jsonPath("$.items[2].id").value(end.id.toString()))
                .andExpect(jsonPath("$.items[0].householdId").doesNotExist())
                .andExpect(jsonPath("$.items[0].memberId").doesNotExist())
                .andExpect(jsonPath("$.totals.expenses.amount").value("59.89"))
                .andExpect(jsonPath("$.totals.expenses.currency").value("KZT"))
                .andExpect(jsonPath("$.totals.incomes.amount").value("400.00"))
                .andExpect(jsonPath("$.totals.incomes.currency").value("KZT"))

            assertThat(accounting.currentBalance(owner.householdId, owner.memberId)).isEqualTo(balance)
        }

        @ParameterizedTest
        @ValueSource(strings = ["EXPENSE", "INCOME"])
        fun `type and single day custom period apply together`(type: String) {
            val setup = openBudget(API_USER_ID)
            val expense = createExpense(setup, TODAY)
            val income = createIncome(setup, TODAY)
            createIncome(setup, TODAY.plusDays(1))
            val expectedId = if (type == "EXPENSE") expense.id else income.id

            mockMvc
                .perform(custom(TODAY, TODAY).queryParam("type", type))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.dateFrom").value(TODAY.toString()))
                .andExpect(jsonPath("$.dateTo").value(TODAY.toString()))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].type").value(type))
                .andExpect(jsonPath("$.items[0].id").value(expectedId.toString()))
                .andExpect(jsonPath("$.totals.expenses.amount").value("100.00"))
                .andExpect(jsonPath("$.totals.incomes.amount").value("200.00"))
        }

        @Test
        fun `year period generates beyond default horizon once and excludes next year`() {
            val setup = openBudget(API_USER_ID)
            createExpense(setup, LocalDate.parse("2026-09-30"), scheduleDay = 31)
            createIncome(setup, LocalDate.parse("2026-12-31"))
            createIncome(setup, LocalDate.parse("2027-01-01"))

            repeat(2) {
                mockMvc
                    .perform(request().queryParam("period", "END_OF_YEAR"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.dateTo").value("2026-12-31"))
                    .andExpect(jsonPath("$.items.length()").value(5))
                    .andExpect(jsonPath("$.items[*].dueOn", hasItem("2026-12-31")))
                    .andExpect(jsonPath("$.items[*].dueOn", not(hasItem("2027-01-01"))))
                    .andExpect(jsonPath("$.totals.expenses.amount").value("400.00"))
                    .andExpect(jsonPath("$.totals.incomes.amount").value("200.00"))
            }
            assertThat(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM recurring_occurrence WHERE household_id = ?",
                    Int::class.java,
                    setup.household.id,
                ),
            ).isEqualTo(4)
            assertThat(accounting.currentBalance(setup.household.id, setup.owner.id).amount).isEqualTo(Money.of("0"))
        }

        @Test
        fun `custom period extends generation beyond year and sorts ties by UUID`() {
            val setup = openBudget(API_USER_ID)
            val day = LocalDate.parse("2027-02-28")
            createExpense(setup, LocalDate.parse("2026-09-30"), scheduleDay = 31)
            val firstIncome = createIncome(setup, day)
            val secondIncome = createIncome(setup, day)

            val result = mockMvc.perform(custom(day, day)).andExpect(status().isOk).andReturn()
            // Repeating the same query must keep both order and generated occurrence IDs stable.
            val repeated = mockMvc.perform(custom(day, day)).andExpect(status().isOk).andReturn()
            assertThat(repeated.response.contentAsString).isEqualTo(result.response.contentAsString)
            mockMvc
                .perform(custom(day, day).queryParam("type", "INCOME"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(
                    jsonPath("$.items[0].id").value(minOf(firstIncome.id.toString(), secondIncome.id.toString())),
                ).andExpect(
                    jsonPath("$.items[1].id").value(maxOf(firstIncome.id.toString(), secondIncome.id.toString())),
                )
            mockMvc
                .perform(custom(day, day).queryParam("type", "EXPENSE"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].dueOn").value("2027-02-28"))
        }

        @Test
        @Suppress("LongMethod") // Terminal states must be excluded while past open plans remain queryable.
        fun `custom past period returns overdue plans and excludes fulfilled cancelled and closed plans`() {
            val setup = openBudget(API_USER_ID)
            val owner = owner(setup)
            val day = LocalDate.parse("2026-09-01")
            val open = createExpense(setup, day)
            val paid = createExpense(setup, day)
            val closed = createExpense(setup, day)
            val cancelled = createExpense(setup, TODAY)
            check(recurring.pay(owner, paid.id, 0, Money.of("100"), day, "test:planned:paid") is PlanChange.Applied)
            check(recurring.pay(owner, closed.id, 0, Money.of("40"), day, "test:planned:closed") is PlanChange.Applied)
            check(recurring.closeOccurrence(owner, closed.id, 1) is PlanChange.Applied)
            check(recurring.cancelOccurrence(owner, cancelled.id, 0, TODAY) is PlanChange.Applied)
            val income = createIncome(setup, day)
            val cancelledIncome = createIncome(setup, day)
            check(recurring.cancelExpectedIncome(owner, cancelledIncome.id, 0) is PlanChange.Applied)
            val received = createIncome(setup, day)
            check(
                recurring.receiveExpectedIncome(
                    owner,
                    received.id,
                    0,
                    Money.of("150"),
                    day,
                    "test:planned:received",
                ) is PlanChange.Applied,
            )

            mockMvc
                .perform(custom(day, TODAY))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[*].id", hasItem(open.id.toString())))
                .andExpect(jsonPath("$.items[*].id", hasItem(income.id.toString())))
                .andExpect(jsonPath("$.items[0].overdue").value(true))
                .andExpect(jsonPath("$.items[1].overdue").value(true))
                .andExpect(jsonPath("$.totals.expenses.amount").value("100.00"))
                .andExpect(jsonPath("$.totals.incomes.amount").value("200.00"))
        }

        @Test
        fun `totals stay complete when the selected type has no items and empty period has zeros`() {
            val setup = openBudget(API_USER_ID)
            createExpense(setup, TODAY)
            mockMvc
                .perform(custom(TODAY, TODAY).queryParam("type", "INCOME"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items").isEmpty)
                .andExpect(jsonPath("$.totals.expenses.amount").value("100.00"))
                .andExpect(jsonPath("$.totals.incomes.amount").value("0.00"))
            mockMvc
                .perform(custom(TODAY.plusDays(1), TODAY.plusDays(1)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items").isEmpty)
                .andExpect(jsonPath("$.totals.expenses.amount").value("0.00"))
                .andExpect(jsonPath("$.totals.incomes.amount").value("0.00"))
        }

        @Test
        fun `income request materializes the full expense horizon for totals idempotently`() {
            val setup = openBudget(API_USER_ID)
            createExpense(setup, LocalDate.parse("2026-09-30"), scheduleDay = 31)
            repeat(2) {
                mockMvc
                    .perform(request().queryParam("type", "INCOME").queryParam("period", "END_OF_YEAR"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.items").isEmpty)
                    .andExpect(jsonPath("$.totals.expenses.amount").value("400.00"))
                    .andExpect(jsonPath("$.totals.incomes.amount").value("0.00"))
            }
            assertThat(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM recurring_occurrence WHERE household_id = ?",
                    Int::class.java,
                    setup.household.id,
                ),
            ).isEqualTo(4)
        }

        @Test
        fun `requires bearer token and active membership`() {
            mockMvc
                .perform(get(PATH))
                .andExpect(status().isUnauthorized)
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"mobile-api\""))
            mockMvc
                .perform(get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer invalid"))
                .andExpect(status().isUnauthorized)
            mockMvc.perform(request()).andExpect(status().isForbidden)
            val setup = openBudget(OWNER_ID)
            addApiMember(setup)
            jdbc.update(
                "UPDATE member SET revoked_at = CURRENT_TIMESTAMP WHERE household_id = ? AND telegram_user_id = ?",
                setup.household.id,
                API_USER_ID,
            )
            mockMvc
                .perform(request())
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("access_denied"))
        }

        @ParameterizedTest
        @ValueSource(
            strings = [
                "type=EXPENSE_REFUND",
                "type=unknown",
                "period=unknown",
                "period=CUSTOM",
                "period=CUSTOM&dateFrom=2026-09-01",
                "period=CUSTOM&dateTo=2026-09-30",
                "period=CUSTOM&dateFrom=2026-09-30&dateTo=2026-09-01",
                "period=CUSTOM&dateFrom=2026-09-01&dateTo=9999-12-31",
                "dateFrom=2026-09-01",
                "period=END_OF_YEAR&dateTo=2026-12-31",
                "period=CUSTOM&dateFrom=2026-02-30&dateTo=2026-09-30",
            ],
        )
        fun `invalid filters return structured bad request`(query: String) {
            openBudget(API_USER_ID)
            val request = request()
            query.split('&').forEach { parameter ->
                val (name, value) = parameter.split('=')
                request.queryParam(name, value)
            }
            mockMvc
                .perform(request)
                .andExpect(status().isBadRequest)
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("invalid_request"))
        }

        private fun createExpense(
            setup: BudgetSetup,
            dueOn: LocalDate,
            scheduleDay: Int = dueOn.dayOfMonth,
        ) = recurring
            .createRule(
                owner(setup),
                RecurrenceFrequency.MONTHLY,
                scheduleDay,
                Money.of("100"),
                setup.categories.single { it.kind == CategoryKind.EXPENSE }.id,
                dueOn,
            ).let { result ->
                check(result is PlanChange.Applied)
                recurring
                    .plannedExpenses(owner(setup), dueOn)
                    .expenses
                    .single { it.occurrence.ruleId == result.value.rule.id && it.occurrence.dueOn == dueOn }
                    .occurrence
            }

        private fun createIncome(
            setup: BudgetSetup,
            dueOn: LocalDate,
        ) = recurring
            .createExpectedIncome(
                owner(setup),
                Money.of("200"),
                dueOn,
                setup.categories.single { it.kind == CategoryKind.INCOME }.id,
                minOf(TODAY, dueOn),
            ).let { result ->
                check(result is PlanChange.Applied)
                result.value.expectedIncome
            }

        private fun openBudget(telegramUserId: Long): BudgetSetup {
            val household = Household(UUID.randomUUID())
            val setup =
                BudgetSetup(
                    household,
                    Member(UUID.randomUUID(), household.id, telegramUserId),
                    Account(UUID.randomUUID(), household.id, Money.of("0"), Instant.parse("2026-08-01T00:00:00Z")),
                    listOf(
                        Category(UUID.randomUUID(), household.id, "Тестовый доход", CategoryKind.INCOME),
                        Category(UUID.randomUUID(), household.id, "Тестовый расход", CategoryKind.EXPENSE),
                    ),
                )
            accounting.openBudget(setup)
            households += household.id
            return setup
        }

        private fun addApiMember(setup: BudgetSetup) {
            jdbc.update(
                "INSERT INTO member(id, household_id, telegram_user_id, role, joined_at) " +
                    "VALUES (?, ?, ?, 'MEMBER', CURRENT_TIMESTAMP)",
                UUID.randomUUID(),
                setup.household.id,
                API_USER_ID,
            )
        }

        private fun owner(setup: BudgetSetup) =
            OwnedLedger(
                setup.household.id,
                setup.owner.id,
                setup.owner.telegramUserId,
                setup.account.accountingStartedAt,
            )

        private fun request() = get(PATH).header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)

        private fun custom(
            from: LocalDate,
            to: LocalDate,
        ) = request()
            .queryParam("period", "CUSTOM")
            .queryParam("dateFrom", from.toString())
            .queryParam("dateTo", to.toString())

        companion object {
            private const val PATH = "/api/v1/planned-operations"
            private const val API_USER_ID = 991002L
            private const val OWNER_ID = 991001L
            private const val AUTHORIZATION = "Bearer synthetic-mobile-token-1234567890"
            private val TODAY = LocalDate.parse("2026-09-06")
        }
    }
