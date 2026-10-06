package com.firkatdavletov.financeconsultante.mobileapi

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.ledger.application.AccountingService
import com.firkatdavletov.financeconsultante.ledger.application.RecordEntry
import com.firkatdavletov.financeconsultante.ledger.domain.Account
import com.firkatdavletov.financeconsultante.ledger.domain.BudgetSetup
import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.ledger.domain.Household
import com.firkatdavletov.financeconsultante.ledger.domain.Member
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.hamcrest.Matchers.nullValue
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
@Suppress("MagicNumber")
class PurchaseHistoryApiTests
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
                jdbc.update("DELETE FROM entry_revision WHERE household_id = ?", household)
                jdbc.update("DELETE FROM ledger_entry WHERE household_id = ?", household)
                jdbc.update("DELETE FROM category WHERE household_id = ?", household)
                jdbc.update("DELETE FROM account WHERE household_id = ?", household)
                jdbc.update("DELETE FROM member WHERE household_id = ?", household)
                jdbc.update("DELETE FROM household WHERE id = ?", household)
            }
        }

        @Test
        fun `returns only household purchases with a stable cursor and denies revoked member`() {
            val setup = openBudget(OWNER_ID)
            addMember(setup, API_USER_ID)
            val first = record(setup, "10.50", Direction.EXPENSE, "mobile:test:purchase:1")
            record(setup, "100", Direction.INCOME, "mobile:test:income")
            val second = record(setup, "20", Direction.EXPENSE, "mobile:test:purchase:2")
            val foreign = openBudget(FOREIGN_OWNER_ID)
            record(foreign, "999", Direction.EXPENSE, "mobile:test:foreign")

            val secondSequence = sequence(second.id)
            mockMvc
                .perform(get("/api/v1/purchases").header(HttpHeaders.AUTHORIZATION, AUTHORIZATION).param("limit", "1"))
                .andExpect(status().isOk)
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(second.id.toString()))
                .andExpect(jsonPath("$.items[0].sequence").value(secondSequence))
                .andExpect(jsonPath("$.items[0].category.name").value("Тестовый расход"))
                .andExpect(jsonPath("$.items[0].originalAmount.amount").value("20.00"))
                .andExpect(jsonPath("$.items[0].originalAmount.currency").value("KZT"))
                .andExpect(jsonPath("$.items[0].amountKzt.amount").value("20.00"))
                .andExpect(jsonPath("$.items[0].occurredOn").value("2026-09-02"))
                .andExpect(jsonPath("$.items[0].status").value("POSTED"))
                .andExpect(jsonPath("$.nextCursor").value(secondSequence))

            mockMvc
                .perform(
                    get("/api/v1/purchases")
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .param("limit", "1")
                        .param("cursor", secondSequence.toString()),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(first.id.toString()))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()))

            jdbc.update(
                "UPDATE member SET revoked_at = CURRENT_TIMESTAMP WHERE household_id = ? AND telegram_user_id = ?",
                setup.household.id,
                API_USER_ID,
            )
            mockMvc
                .perform(get("/api/v1/purchases").header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("access_denied"))
        }

        @Test
        fun `requires bearer token and validates pagination`() {
            mockMvc
                .perform(get("/api/v1/purchases"))
                .andExpect(status().isUnauthorized)
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"mobile-api\""))
                .andExpect(jsonPath("$.code").value("unauthorized"))
            mockMvc
                .perform(get("/api/v1/purchases").header(HttpHeaders.AUTHORIZATION, "Bearer wrong"))
                .andExpect(status().isUnauthorized)
            mockMvc
                .perform(
                    get("/api/v1/purchases")
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .param("limit", "51"),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("invalid_request"))
            mockMvc
                .perform(
                    get("/api/v1/purchases")
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .param("cursor", "not-a-number"),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("invalid_request"))
            mockMvc
                .perform(get("/api/v1/purchases").header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isForbidden)
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

        private fun record(
            setup: BudgetSetup,
            amount: String,
            direction: Direction,
            source: String,
        ) = accounting
            .record(
                RecordEntry(
                    setup.household.id,
                    setup.owner.id,
                    setup.categories.single { it.kind == direction.categoryKind }.id,
                    direction,
                    Money.of(amount),
                    OCCURRED_AT,
                    source,
                ),
            ).entry

        private fun sequence(entryId: UUID): Long =
            checkNotNull(
                jdbc.queryForObject(
                    "SELECT history_sequence FROM ledger_entry WHERE id = ?",
                    Long::class.java,
                    entryId,
                ),
            )

        companion object {
            private const val API_USER_ID = 991002L
            private const val OWNER_ID = 991001L
            private const val FOREIGN_OWNER_ID = 991003L
            private const val AUTHORIZATION = "Bearer synthetic-mobile-token-1234567890"
            private val ACCOUNTING_START = Instant.parse("2026-09-01T00:00:00Z")
            private val OCCURRED_AT = Instant.parse("2026-09-02T00:00:00Z")
        }
    }
