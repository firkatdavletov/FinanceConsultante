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
import com.firkatdavletov.financeconsultante.recurring.domain.ExpectedIncomeView
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
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
@Suppress("MagicNumber")
class PlannedIncomeApiTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val accounting: AccountingService,
        private val recurring: RecurringPlanService,
        private val jdbc: JdbcTemplate,
        private val clock: Clock,
    ) : PostgresIntegrationTest() {
        private val households = mutableListOf<UUID>()

        @AfterEach
        fun cleanup() {
            households.forEach { household ->
                jdbc.update("DELETE FROM expected_income_match WHERE household_id = ?", household)
                jdbc.update("DELETE FROM expected_income WHERE household_id = ?", household)
                jdbc.update("DELETE FROM entry_revision WHERE household_id = ?", household)
                jdbc.update("DELETE FROM ledger_entry WHERE household_id = ?", household)
                jdbc.update("DELETE FROM category WHERE household_id = ?", household)
                jdbc.update("DELETE FROM account WHERE household_id = ?", household)
                jdbc.update("DELETE FROM member WHERE household_id = ?", household)
                jdbc.update("DELETE FROM household WHERE id = ?", household)
            }
        }

        @Test
        fun `returns only open household planned incomes ordered by due date`() {
            val today = LocalDate.now(clock.withZone(API_ZONE))
            val setup = openBudget(OWNER_ID)
            addMember(setup, API_USER_ID)
            val owner = owner(setup, OWNER_ID)
            val overdue = createIncome(owner, setup.incomeCategory(), today.minusDays(1), "1000")
            val future = createIncome(owner, setup.incomeCategory(), today.plusDays(5), "2000")
            val cancelled = createIncome(owner, setup.incomeCategory(), today.plusDays(6), "3000")
            check(
                recurring.cancelExpectedIncome(
                    owner,
                    cancelled.expectedIncome.id,
                    cancelled.expectedIncome.version,
                    today,
                ) is PlanChange.Applied,
            )
            val received = createIncome(owner, setup.incomeCategory(), today.plusDays(7), "4000")
            check(
                recurring.receiveExpectedIncome(
                    owner,
                    received.expectedIncome.id,
                    received.expectedIncome.version,
                    Money.of("4100"),
                    today,
                    "mobile:planned-income:received",
                ) is PlanChange.Applied,
            )

            val foreign = openBudget(FOREIGN_OWNER_ID)
            val foreignIncome =
                createIncome(
                    owner(foreign, FOREIGN_OWNER_ID),
                    foreign.incomeCategory(),
                    today,
                    "9999",
                )

            mockMvc
                .perform(get("/api/v1/planned-incomes").header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isOk)
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.asOf").value(today.toString()))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(overdue.expectedIncome.id.toString()))
                .andExpect(jsonPath("$.items[0].category.name").value("Тестовый доход"))
                .andExpect(jsonPath("$.items[0].plannedAmount.amount").value("1000.00"))
                .andExpect(jsonPath("$.items[0].plannedAmount.currency").value("KZT"))
                .andExpect(jsonPath("$.items[0].dueOn").value(today.minusDays(1).toString()))
                .andExpect(jsonPath("$.items[0].overdue").value(true))
                .andExpect(jsonPath("$.items[0].status").value("SCHEDULED"))
                .andExpect(jsonPath("$.items[1].id").value(future.expectedIncome.id.toString()))
                .andExpect(jsonPath("$.items[1].overdue").value(false))
                .andExpect(
                    jsonPath("$.items[*].id", not(hasItem(cancelled.expectedIncome.id.toString()))),
                ).andExpect(
                    jsonPath("$.items[*].id", not(hasItem(received.expectedIncome.id.toString()))),
                ).andExpect(
                    jsonPath("$.items[*].id", not(hasItem(foreignIncome.expectedIncome.id.toString()))),
                )
        }

        @Test
        fun `requires bearer token and active membership`() {
            mockMvc
                .perform(get("/api/v1/planned-incomes"))
                .andExpect(status().isUnauthorized)
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"mobile-api\""))
                .andExpect(jsonPath("$.code").value("unauthorized"))

            val setup = openBudget(OWNER_ID)
            addMember(setup, API_USER_ID)
            jdbc.update(
                "UPDATE member SET revoked_at = CURRENT_TIMESTAMP WHERE household_id = ? AND telegram_user_id = ?",
                setup.household.id,
                API_USER_ID,
            )

            mockMvc
                .perform(get("/api/v1/planned-incomes").header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("access_denied"))
        }

        private fun createIncome(
            owner: OwnedLedger,
            category: Category,
            dueOn: LocalDate,
            amount: String,
        ): ExpectedIncomeView {
            val result =
                recurring.createExpectedIncome(
                    owner,
                    Money.of(amount),
                    dueOn,
                    category.id,
                    dueOn.minusDays(1),
                )
            check(result is PlanChange.Applied)
            return result.value
        }

        private fun openBudget(telegramUserId: Long): BudgetSetup {
            val household = Household(UUID.randomUUID())
            val setup =
                BudgetSetup(
                    household,
                    Member(UUID.randomUUID(), household.id, telegramUserId),
                    Account(UUID.randomUUID(), household.id, Money.of("0"), ACCOUNTING_START),
                    listOf(
                        Category(UUID.randomUUID(), household.id, "Тестовый доход", CategoryKind.INCOME),
                        Category(UUID.randomUUID(), household.id, "Тестовый расход", CategoryKind.EXPENSE),
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

        private fun owner(
            setup: BudgetSetup,
            telegramUserId: Long,
        ) = OwnedLedger(setup.household.id, setup.owner.id, telegramUserId, ACCOUNTING_START)

        private fun BudgetSetup.incomeCategory(): Category = categories.single { it.kind == CategoryKind.INCOME }

        companion object {
            private const val API_USER_ID = 991002L
            private const val OWNER_ID = 991001L
            private const val FOREIGN_OWNER_ID = 991003L
            private const val AUTHORIZATION = "Bearer synthetic-mobile-token-1234567890"
            private val API_ZONE = ZoneId.of("Asia/Almaty")
            private val ACCOUNTING_START = Instant.parse("2026-09-01T00:00:00Z")
        }
    }
