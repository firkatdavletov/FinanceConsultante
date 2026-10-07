package com.firkatdavletov.financeconsultante.mobileapi

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.ledger.application.AccountingService
import com.firkatdavletov.financeconsultante.ledger.domain.Account
import com.firkatdavletov.financeconsultante.ledger.domain.BudgetSetup
import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryIcon
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import com.firkatdavletov.financeconsultante.ledger.domain.Household
import com.firkatdavletov.financeconsultante.ledger.domain.Member
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
import java.time.Instant
import java.util.UUID

@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "app.mobile-api.enabled=true",
        "app.mobile-api.token=synthetic-mobile-token-1234567890",
        "app.mobile-api.telegram-user-id=991002",
    ],
)
class CategoryApiTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val accounting: AccountingService,
        private val jdbc: JdbcTemplate,
    ) : PostgresIntegrationTest() {
        private val households = mutableListOf<UUID>()

        @AfterEach
        fun cleanup() {
            households.forEach { household ->
                jdbc.update("DELETE FROM category WHERE household_id = ?", household)
                jdbc.update("DELETE FROM account WHERE household_id = ?", household)
                jdbc.update("DELETE FROM member WHERE household_id = ?", household)
                jdbc.update("DELETE FROM household WHERE id = ?", household)
            }
        }

        @Test
        fun `returns active categories from authenticated member household`() {
            val setup = openBudget(OWNER_ID)
            addMember(setup, API_USER_ID)
            val activeExpense = setup.categories[0]
            val activeIncome = setup.categories[1]
            val archived = setup.categories[2]
            jdbc.update(
                "UPDATE category SET archived = TRUE WHERE household_id = ? AND id = ?",
                setup.household.id,
                archived.id,
            )
            val foreign = openBudget(FOREIGN_OWNER_ID).categories.single()

            mockMvc
                .perform(get("/api/v1/categories").header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isOk)
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(activeExpense.id.toString()))
                .andExpect(jsonPath("$.items[0].name").value(activeExpense.name))
                .andExpect(jsonPath("$.items[0].kind").value("EXPENSE"))
                .andExpect(jsonPath("$.items[0].icon.code").value("FOOD"))
                .andExpect(jsonPath("$.items[0].icon.emoji").value("🍔"))
                .andExpect(jsonPath("$.items[1].id").value(activeIncome.id.toString()))
                .andExpect(jsonPath("$.items[1].kind").value("INCOME"))
                .andExpect(jsonPath("$.items[1].icon.code").value("INCOME"))
                .andExpect(jsonPath("$.items[*].id", not(hasItem(archived.id.toString()))))
                .andExpect(jsonPath("$.items[*].id", not(hasItem(foreign.id.toString()))))
        }

        @Test
        fun `requires bearer token and active membership`() {
            mockMvc
                .perform(get("/api/v1/categories"))
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
                .perform(get("/api/v1/categories").header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("access_denied"))
        }

        private fun openBudget(telegramUserId: Long): BudgetSetup {
            val household = Household(UUID.randomUUID())
            val categories =
                if (telegramUserId == FOREIGN_OWNER_ID) {
                    listOf(Category(UUID.randomUUID(), household.id, "Foreign", CategoryKind.EXPENSE))
                } else {
                    listOf(
                        Category(
                            UUID.randomUUID(),
                            household.id,
                            "A expense",
                            CategoryKind.EXPENSE,
                            icon = CategoryIcon.FOOD,
                        ),
                        Category(
                            UUID.randomUUID(),
                            household.id,
                            "B income",
                            CategoryKind.INCOME,
                            icon = CategoryIcon.INCOME,
                        ),
                        Category(UUID.randomUUID(), household.id, "C archived", CategoryKind.EXPENSE),
                    )
                }
            val setup =
                BudgetSetup(
                    household,
                    Member(UUID.randomUUID(), household.id, telegramUserId),
                    Account(UUID.randomUUID(), household.id, Money.of("0"), ACCOUNTING_START),
                    categories,
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

        companion object {
            private const val API_USER_ID = 991002L
            private const val OWNER_ID = 991001L
            private const val FOREIGN_OWNER_ID = 991003L
            private const val AUTHORIZATION = "Bearer synthetic-mobile-token-1234567890"
            private val ACCOUNTING_START = Instant.parse("2026-09-01T00:00:00Z")
        }
    }
