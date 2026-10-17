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
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
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
@Suppress("MagicNumber", "TooManyFunctions")
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

        @Test
        @Suppress("LongMethod") // One contract scenario verifies create, patch, readback, and ledger invariance.
        fun `owner creates and edits a planned income without changing balance`() {
            val today = LocalDate.now(clock.withZone(API_ZONE))
            val setup = openBudget(API_USER_ID)
            val originalCategory = setup.incomeCategory()
            val updatedCategory = setup.categories.last { it.kind == CategoryKind.INCOME }

            mockMvc
                .perform(
                    post("/api/v1/planned-incomes")
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {
                              "categoryId": "${originalCategory.id}",
                              "amount": "100000.00",
                              "dueOn": "${today.plusDays(2)}"
                            }
                            """.trimIndent(),
                        ),
                ).andExpect(status().isCreated)
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.category.id").value(originalCategory.id.toString()))
                .andExpect(jsonPath("$.plannedAmount.amount").value("100000.00"))
                .andExpect(jsonPath("$.dueOn").value(today.plusDays(2).toString()))
                .andExpect(jsonPath("$.version").value(0))

            val expectedIncomeId =
                checkNotNull(
                    jdbc.queryForObject(
                        "SELECT id FROM expected_income WHERE household_id = ?",
                        UUID::class.java,
                        setup.household.id,
                    ),
                )
            assertThatBalanceIsZero(setup)

            mockMvc
                .perform(
                    patch("/api/v1/planned-incomes/{expectedIncomeId}", expectedIncomeId)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {
                              "version": 0,
                              "categoryId": "${updatedCategory.id}",
                              "amount": "125000.50",
                              "dueOn": "${today.plusDays(4)}"
                            }
                            """.trimIndent(),
                        ),
                ).andExpect(status().isOk)
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.id").value(expectedIncomeId.toString()))
                .andExpect(jsonPath("$.category.id").value(updatedCategory.id.toString()))
                .andExpect(jsonPath("$.category.name").value("Тестовый бонус"))
                .andExpect(jsonPath("$.plannedAmount.amount").value("125000.50"))
                .andExpect(jsonPath("$.dueOn").value(today.plusDays(4).toString()))
                .andExpect(jsonPath("$.version").value(1))
            assertThatBalanceIsZero(setup)

            mockMvc
                .perform(get("/api/v1/planned-incomes").header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(expectedIncomeId.toString()))
                .andExpect(jsonPath("$.items[0].plannedAmount.amount").value("125000.50"))
                .andExpect(jsonPath("$.items[0].version").value(1))
        }

        @ParameterizedTest
        @ValueSource(longs = [-1, 0, 3])
        fun `owner deletes an open planned income including overdue without changing balance`(daysFromToday: Long) {
            val today = LocalDate.now(clock.withZone(API_ZONE))
            val setup = openBudget(API_USER_ID)
            val owner = owner(setup, API_USER_ID)
            val income = createIncome(owner, setup.incomeCategory(), today.plusDays(daysFromToday), "100")

            mockMvc
                .perform(
                    delete("/api/v1/planned-incomes/{id}", income.expectedIncome.id)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .queryParam("version", income.expectedIncome.version.toString()),
                ).andExpect(status().isNoContent)
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().string(""))

            mockMvc
                .perform(get("/api/v1/planned-incomes").header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(0))
            assertThat(
                recurring
                    .expectedIncome(owner, income.expectedIncome.id)
                    ?.expectedIncome
                    ?.status
                    ?.name,
            ).isEqualTo("CANCELLED")
            assertThat(recurring.expectedIncome(owner, income.expectedIncome.id)?.expectedIncome?.version).isEqualTo(1)
            assertThat(recurring.expectedIncomeForecast(owner, today.plusDays(10))).isEmpty()
            assertThatBalanceIsZero(setup)
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM ledger_entry WHERE household_id = ?",
                    Long::class.java,
                    setup.household.id,
                ),
            ).isZero()

            mockMvc
                .perform(
                    delete("/api/v1/planned-incomes/{id}", income.expectedIncome.id)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .queryParam("version", income.expectedIncome.version.toString()),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("planned_income_conflict"))
        }

        @Test
        fun `delete requires authentication and active owner membership`() {
            mockMvc
                .perform(delete("/api/v1/planned-incomes/{id}", UUID.randomUUID()).queryParam("version", "0"))
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.code").value("unauthorized"))

            val setup = openBudget(OWNER_ID)
            addMember(setup, API_USER_ID)
            val income =
                createIncome(
                    owner(setup, OWNER_ID),
                    setup.incomeCategory(),
                    LocalDate.now(clock.withZone(API_ZONE)),
                    "100",
                )
            mockMvc
                .perform(
                    delete("/api/v1/planned-incomes/{id}", income.expectedIncome.id)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .queryParam("version", "0"),
                ).andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("owner_required"))

            jdbc.update(
                "UPDATE member SET revoked_at = CURRENT_TIMESTAMP WHERE household_id = ? AND telegram_user_id = ?",
                setup.household.id,
                API_USER_ID,
            )
            mockMvc
                .perform(
                    delete("/api/v1/planned-incomes/{id}", income.expectedIncome.id)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .queryParam("version", "0"),
                ).andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("access_denied"))
            assertThat(
                recurring.expectedIncome(owner(setup, OWNER_ID), income.expectedIncome.id)?.expectedIncome?.version,
            ).isZero()
        }

        @Test
        fun `delete rejects missing and invalid versions and hides foreign or missing plans`() {
            val today = LocalDate.now(clock.withZone(API_ZONE))
            val setup = openBudget(API_USER_ID)
            val owner = owner(setup, API_USER_ID)
            val income = createIncome(owner, setup.incomeCategory(), today, "100")
            listOf(null, "-1", "not-a-version").forEach { version ->
                val request =
                    delete("/api/v1/planned-incomes/{id}", income.expectedIncome.id)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                if (version != null) request.queryParam("version", version)
                mockMvc.perform(request).andExpect(status().isBadRequest)
            }

            val foreign = openBudget(FOREIGN_OWNER_ID)
            val foreignIncome =
                createIncome(owner(foreign, FOREIGN_OWNER_ID), foreign.incomeCategory(), today, "200")
            listOf(foreignIncome.expectedIncome.id, UUID.randomUUID()).forEach { id ->
                mockMvc
                    .perform(
                        delete("/api/v1/planned-incomes/{id}", id)
                            .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                            .queryParam("version", "0"),
                    ).andExpect(status().isNotFound)
                    .andExpect(jsonPath("$.code").value("planned_income_not_found"))
            }
            assertThat(recurring.expectedIncome(owner, income.expectedIncome.id)?.expectedIncome?.version).isZero()
        }

        @Test
        fun `delete rejects stale and received plans without changing actual income`() {
            val today = LocalDate.now(clock.withZone(API_ZONE))
            val setup = openBudget(API_USER_ID)
            val owner = owner(setup, API_USER_ID)
            val income = createIncome(owner, setup.incomeCategory(), today, "100")
            mockMvc
                .perform(
                    delete("/api/v1/planned-incomes/{id}", income.expectedIncome.id)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .queryParam("version", "7"),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("planned_income_conflict"))

            check(
                recurring.receiveExpectedIncome(
                    owner,
                    income.expectedIncome.id,
                    income.expectedIncome.version,
                    Money.of("120"),
                    today,
                    "synthetic:mobile:received",
                ) is PlanChange.Applied,
            )
            mockMvc
                .perform(
                    delete("/api/v1/planned-incomes/{id}", income.expectedIncome.id)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .queryParam("version", "1"),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("planned_income_conflict"))

            assertThat(recurring.expectedIncome(owner, income.expectedIncome.id)?.fulfilled).isTrue()
            assertThat(accounting.currentBalance(setup.household.id, setup.owner.id).amount).isEqualTo(Money.of("120"))
        }

        @Test
        fun `planned income mutations require owner valid input and current version`() {
            val today = LocalDate.now(clock.withZone(API_ZONE))
            val memberBudget = openBudget(OWNER_ID)
            addMember(memberBudget, API_USER_ID)

            mockMvc
                .perform(
                    post("/api/v1/planned-incomes")
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest(memberBudget.incomeCategory(), today, "100")),
                ).andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("owner_required"))

            jdbc.update(
                "UPDATE member SET revoked_at = CURRENT_TIMESTAMP WHERE household_id = ? AND telegram_user_id = ?",
                memberBudget.household.id,
                API_USER_ID,
            )
            val ownerBudget = openBudget(API_USER_ID)

            mockMvc
                .perform(
                    post("/api/v1/planned-incomes")
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest(ownerBudget.incomeCategory(), today, "1.001")),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("invalid_planned_income"))

            val expected = createIncome(owner(ownerBudget, API_USER_ID), ownerBudget.incomeCategory(), today, "100")
            mockMvc
                .perform(
                    patch("/api/v1/planned-incomes/{expectedIncomeId}", expected.expectedIncome.id)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"version":7,"amount":"200"}"""),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("planned_income_conflict"))

            mockMvc
                .perform(
                    patch("/api/v1/planned-incomes/{expectedIncomeId}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"version":0,"amount":"200"}"""),
                ).andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("planned_income_not_found"))
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
                        Category(UUID.randomUUID(), household.id, "Тестовый бонус", CategoryKind.INCOME),
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

        private fun BudgetSetup.incomeCategory(): Category = categories.first { it.kind == CategoryKind.INCOME }

        private fun assertThatBalanceIsZero(setup: BudgetSetup) {
            assertThat(accounting.currentBalance(setup.household.id, setup.owner.id).amount).isEqualTo(Money.of("0"))
        }

        private fun createRequest(
            category: Category,
            dueOn: LocalDate,
            amount: String,
        ) = """
            {
              "categoryId": "${category.id}",
              "amount": "$amount",
              "dueOn": "$dueOn"
            }
            """.trimIndent()

        companion object {
            private const val API_USER_ID = 991002L
            private const val OWNER_ID = 991001L
            private const val FOREIGN_OWNER_ID = 991003L
            private const val AUTHORIZATION = "Bearer synthetic-mobile-token-1234567890"
            private val API_ZONE = ZoneId.of("Asia/Almaty")
            private val ACCOUNTING_START = Instant.parse("2026-09-01T00:00:00Z")
        }
    }
