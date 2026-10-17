package com.firkatdavletov.financeconsultante.mobileapi

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.ledger.application.AccountingService
import com.firkatdavletov.financeconsultante.ledger.application.CorrectionResult
import com.firkatdavletov.financeconsultante.ledger.application.EntryCorrections
import com.firkatdavletov.financeconsultante.ledger.application.EntryTarget
import com.firkatdavletov.financeconsultante.ledger.application.RecordEntry
import com.firkatdavletov.financeconsultante.ledger.domain.Account
import com.firkatdavletov.financeconsultante.ledger.domain.BudgetSetup
import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.ledger.domain.EntryChange
import com.firkatdavletov.financeconsultante.ledger.domain.Household
import com.firkatdavletov.financeconsultante.ledger.domain.Member
import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
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
@Suppress("MagicNumber")
class OperationHistoryApiTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val accounting: AccountingService,
        private val corrections: EntryCorrections,
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
        fun `returns income and expenses including voided entries but excludes refunds and other households`() {
            val setup = openBudget(OWNER_ID)
            addApiMember(setup)
            val expense = record(setup, Direction.EXPENSE, "expense")
            val income = record(setup, Direction.INCOME, "income")
            assertThat(
                corrections.refund(
                    EntryTarget(setup.household.id, setup.owner.id, expense.id, 0),
                    Money.of("2"),
                    LocalDate.parse("2026-09-03"),
                    "mobile:test:refund",
                ),
            ).isInstanceOf(CorrectionResult.Updated::class.java)
            assertThat(
                corrections.change(
                    EntryTarget(setup.household.id, setup.owner.id, income.id, 0),
                    EntryChange.Void,
                    "mobile:test:void",
                ),
            ).isInstanceOf(CorrectionResult.Updated::class.java)
            record(openBudget(FOREIGN_USER_ID), Direction.INCOME, "foreign")

            mockMvc
                .perform(request())
                .andExpect(status().isOk)
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(income.id.toString()))
                .andExpect(jsonPath("$.items[0].type").value("INCOME"))
                .andExpect(jsonPath("$.items[0].status").value("VOIDED"))
                .andExpect(jsonPath("$.items[0].originalAmount.amount").value("10.00"))
                .andExpect(jsonPath("$.items[0].category.name").value("Тестовый доход"))
                .andExpect(jsonPath("$.items[0].householdId").doesNotExist())
                .andExpect(jsonPath("$.items[0].actorId").doesNotExist())
                .andExpect(jsonPath("$.items[0].sourceKey").doesNotExist())
                .andExpect(jsonPath("$.items[1].id").value(expense.id.toString()))
                .andExpect(jsonPath("$.items[1].type").value("EXPENSE"))
                .andExpect(jsonPath("$.totals.expenses.amount").value("8.00"))
                .andExpect(jsonPath("$.totals.expenses.currency").value("KZT"))
                .andExpect(jsonPath("$.totals.incomes.amount").value("0.00"))
                .andExpect(jsonPath("$.totals.incomes.currency").value("KZT"))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()))
        }

        @Test
        fun `combines all filters before pagination with inclusive local dates and stable cursor`() {
            val setup = openBudget(API_USER_ID)
            val categoryId = setup.categories.first { it.kind == CategoryKind.EXPENSE }.id
            val start = record(setup, Direction.EXPENSE, "start", "2026-09-01T19:00:00Z")
            record(setup, Direction.EXPENSE, "before", "2026-09-01T18:59:59.999999Z")
            val end = record(setup, Direction.EXPENSE, "end", "2026-09-03T18:59:59.999999Z")
            record(setup, Direction.EXPENSE, "category", categoryId = setup.categories.last().id)
            record(setup, Direction.EXPENSE, "after", "2026-09-03T19:00:00Z")
            record(setup, Direction.INCOME, "income")
            val nextCursor =
                checkNotNull(
                    jdbc.queryForObject(
                        "SELECT history_sequence FROM ledger_entry WHERE id = ?",
                        Long::class.java,
                        end.id,
                    ),
                ).toString()

            mockMvc
                .perform(filteredRequest(categoryId).param("limit", "1"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(end.id.toString()))
                .andExpect(jsonPath("$.items[0].occurredOn").value("2026-09-03"))
                .andExpect(jsonPath("$.nextCursor").value(nextCursor.toLong()))
                .andExpect(jsonPath("$.totals.expenses.amount").value("30.00"))
                .andExpect(jsonPath("$.totals.incomes.amount").value("10.00"))

            record(setup, Direction.EXPENSE, "newer")
            mockMvc
                .perform(filteredRequest(categoryId).param("limit", "1").param("cursor", nextCursor))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(start.id.toString()))
                .andExpect(jsonPath("$.items[0].occurredOn").value("2026-09-02"))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()))
                .andExpect(jsonPath("$.totals.expenses.amount").value("40.00"))
                .andExpect(jsonPath("$.totals.incomes.amount").value("10.00"))
        }

        @Test
        fun `supports individual filters open date bounds and empty results`() {
            val setup = openBudget(API_USER_ID)
            val expense = record(setup, Direction.EXPENSE, "early", "2026-09-01T19:00:00Z")
            val income = record(setup, Direction.INCOME, "late", "2026-09-03T19:00:00Z")
            listOf("type" to "INCOME", "categoryId" to income.categoryId.toString(), "dateFrom" to "2026-09-03")
                .forEach { (name, value) ->
                    mockMvc
                        .perform(request().param(name, value))
                        .andExpect(status().isOk)
                        .andExpect(jsonPath("$.items.length()").value(1))
                        .andExpect(jsonPath("$.items[0].id").value(income.id.toString()))
                }
            mockMvc
                .perform(request().param("dateTo", "2026-09-02"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items[0].id").value(expense.id.toString()))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totals.expenses.amount").value("10.00"))
                .andExpect(jsonPath("$.totals.incomes.amount").value("0.00"))
            mockMvc
                .perform(request().param("dateFrom", "2026-09-02").param("dateTo", "2026-09-02"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(1))
            val foreign = openBudget(FOREIGN_USER_ID)
            val foreignExpense = record(foreign, Direction.EXPENSE, "foreign")
            listOf(foreignExpense.categoryId, UUID.randomUUID()).forEach { categoryId ->
                mockMvc
                    .perform(request().param("categoryId", categoryId.toString()))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.items").isEmpty)
                    .andExpect(jsonPath("$.nextCursor").value(nullValue()))
            }
            mockMvc
                .perform(request().param("type", "INCOME").param("categoryId", expense.categoryId.toString()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items").isEmpty)
        }

        @Nested
        inner class PeriodTotals {
            @Test
            fun `empty period has zero totals even when another period contains operations`() {
                val setup = openBudget(API_USER_ID)
                mockMvc
                    .perform(request())
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.totals.expenses.amount").value("0.00"))
                    .andExpect(jsonPath("$.totals.incomes.amount").value("0.00"))
                record(setup, Direction.EXPENSE, "outside-period")
                mockMvc
                    .perform(request().param("dateFrom", "2026-09-04").param("dateTo", "2026-09-04"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.items").isEmpty)
                    .andExpect(jsonPath("$.totals.expenses.amount").value("0.00"))
                    .andExpect(jsonPath("$.totals.incomes.amount").value("0.00"))
            }

            @Test
            fun `totals are identical on every page and independent of type and category`() {
                val setup = openBudget(API_USER_ID)
                val expense = record(setup, Direction.EXPENSE, "expense")
                val income = record(setup, Direction.INCOME, "income")
                val cursor =
                    checkNotNull(
                        jdbc.queryForObject(
                            "SELECT history_sequence FROM ledger_entry WHERE id = ?",
                            Long::class.java,
                            expense.id,
                        ),
                    ).toString()
                listOf(
                    request(),
                    request().param("limit", "1"),
                    request().param("cursor", cursor),
                    request().param("type", "EXPENSE"),
                    request().param("type", "INCOME"),
                    request().param("categoryId", expense.categoryId.toString()),
                    request().param("type", "EXPENSE").param("categoryId", income.categoryId.toString()),
                    request().param("dateFrom", "2026-09-02"),
                ).forEach { query ->
                    mockMvc
                        .perform(query)
                        .andExpect(status().isOk)
                        .andExpect(jsonPath("$.totals.expenses.amount").value("10.00"))
                        .andExpect(jsonPath("$.totals.incomes.amount").value("10.00"))
                }
            }

            @Test
            fun `refunds reduce expenses on their own date and can make the period negative`() {
                val setup = openBudget(API_USER_ID)
                val expense = record(setup, Direction.EXPENSE, "expense", "2026-09-01T12:00:00Z")
                assertThat(
                    corrections.refund(
                        EntryTarget(setup.household.id, setup.owner.id, expense.id, 0),
                        Money.of("2.15"),
                        LocalDate.parse("2026-09-03"),
                        "mobile:test:refund-period",
                    ),
                ).isInstanceOf(CorrectionResult.Updated::class.java)
                mockMvc
                    .perform(request().param("dateFrom", "2026-09-03").param("dateTo", "2026-09-03"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.items").isEmpty)
                    .andExpect(jsonPath("$.totals.expenses.amount").value("-2.15"))
                    .andExpect(jsonPath("$.totals.incomes.amount").value("0.00"))
                mockMvc
                    .perform(request().param("dateTo", "2026-09-01"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.totals.expenses.amount").value("10.00"))
            }

            @Test
            fun `totals use stored KZT valuations and reflect corrections without including voided expenses`() {
                val setup = openBudget(API_USER_ID)
                jdbc.update(
                    "INSERT INTO daily_exchange_rate(rate_date, rub_kzt, source, quoted_at, fetched_at) " +
                        "VALUES ('2026-09-04', 5.125, 'MOBILE_TOTALS_TEST', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                )
                try {
                    record(
                        setup,
                        Direction.EXPENSE,
                        "rub",
                        "2026-09-04T12:00:00Z",
                        amount = Money.of("1.23", Currency.RUB),
                    )
                    val expense = record(setup, Direction.EXPENSE, "changed")
                    val voided = record(setup, Direction.EXPENSE, "voided")
                    assertThat(
                        corrections.change(
                            EntryTarget(setup.household.id, setup.owner.id, expense.id, 0),
                            EntryChange.Amount(Money.of("10.11")),
                            "mobile:test:change-total",
                        ),
                    ).isInstanceOf(CorrectionResult.Updated::class.java)
                    assertThat(
                        corrections.change(
                            EntryTarget(setup.household.id, setup.owner.id, voided.id, 0),
                            EntryChange.Void,
                            "mobile:test:void-expense",
                        ),
                    ).isInstanceOf(CorrectionResult.Updated::class.java)
                    mockMvc
                        .perform(request())
                        .andExpect(status().isOk)
                        .andExpect(jsonPath("$.totals.expenses.amount").value("16.41"))
                        .andExpect(jsonPath("$.totals.incomes.amount").value("0.00"))
                } finally {
                    cleanup()
                    households.clear()
                    jdbc.update("DELETE FROM daily_exchange_rate WHERE rate_date = '2026-09-04'")
                }
            }
        }

        @ParameterizedTest
        @CsvSource(
            "type,UNKNOWN",
            "type,EXPENSE_REFUND",
            "categoryId,not-a-uuid",
            "dateFrom,2026-02-30",
            "dateTo,03.09.2026",
            "cursor,0",
            "cursor,not-a-number",
            "limit,0",
            "limit,51",
        )
        fun `rejects invalid filters and pagination`(
            name: String,
            value: String,
        ) {
            mockMvc
                .perform(request().param(name, value))
                .andExpect(status().isBadRequest)
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("invalid_request"))
        }

        @Test
        fun `rejects reversed period and requires active membership and bearer token`() {
            mockMvc
                .perform(request().param("dateFrom", "2026-09-03").param("dateTo", "2026-09-01"))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("invalid_request"))
            mockMvc
                .perform(get("/api/v1/operations"))
                .andExpect(status().isUnauthorized)
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"mobile-api\""))
            mockMvc
                .perform(get("/api/v1/operations").header(HttpHeaders.AUTHORIZATION, "Bearer wrong"))
                .andExpect(status().isUnauthorized)
            mockMvc.perform(request()).andExpect(status().isForbidden)
            val setup = openBudget(OWNER_ID)
            val memberId = addApiMember(setup)
            record(setup, Direction.INCOME, "income")
            mockMvc.perform(request()).andExpect(status().isOk)
            jdbc.update("UPDATE member SET revoked_at = CURRENT_TIMESTAMP WHERE id = ?", memberId)
            mockMvc
                .perform(request())
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("access_denied"))
        }

        private fun request() = get("/api/v1/operations").header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)

        private fun addApiMember(setup: BudgetSetup): UUID {
            val memberId = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO member(id, household_id, telegram_user_id, role, joined_at) " +
                    "VALUES (?, ?, ?, 'MEMBER', CURRENT_TIMESTAMP)",
                memberId,
                setup.household.id,
                API_USER_ID,
            )
            return memberId
        }

        private fun filteredRequest(categoryId: UUID) =
            request()
                .param("type", "EXPENSE")
                .param("categoryId", categoryId.toString())
                .param("dateFrom", "2026-09-02")
                .param("dateTo", "2026-09-03")

        private fun openBudget(telegramUserId: Long): BudgetSetup {
            val household = Household(UUID.randomUUID())
            val setup =
                BudgetSetup(
                    household,
                    Member(UUID.randomUUID(), household.id, telegramUserId),
                    Account(UUID.randomUUID(), household.id, Money.of("0"), Instant.parse("2026-09-01T00:00:00Z")),
                    listOf(
                        Category(UUID.randomUUID(), household.id, "Тестовый доход", CategoryKind.INCOME),
                        Category(UUID.randomUUID(), household.id, "Тестовый расход", CategoryKind.EXPENSE),
                        Category(UUID.randomUUID(), household.id, "Другой расход", CategoryKind.EXPENSE),
                    ),
                )
            accounting.openBudget(setup)
            households += household.id
            return setup
        }

        private fun record(
            setup: BudgetSetup,
            direction: Direction,
            source: String,
            occurredAt: String = "2026-09-02T12:00:00Z",
            categoryId: UUID = setup.categories.first { it.kind == direction.categoryKind }.id,
            amount: Money = Money.of("10"),
        ) = accounting
            .record(
                RecordEntry(
                    setup.household.id,
                    setup.owner.id,
                    categoryId,
                    direction,
                    amount,
                    Instant.parse(occurredAt),
                    "mobile:test:$source",
                ),
            ).entry

        companion object {
            private const val API_USER_ID = 991002L
            private const val OWNER_ID = 991001L
            private const val FOREIGN_USER_ID = 991003L
            private const val AUTHORIZATION = "Bearer synthetic-mobile-token-1234567890"
        }
    }
