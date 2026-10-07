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
import java.time.YearMonth
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
class PlannedExpenseApiTests
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
        fun `returns open household planned expenses with paid and remaining amounts`() {
            val today = LocalDate.now(clock.withZone(API_ZONE))
            val setup = openBudget(OWNER_ID)
            addMember(setup, API_USER_ID)
            val owner = owner(setup, OWNER_ID)
            val rule = createRule(owner, setup.expenseCategory(), today, "100")
            val occurrences =
                recurring
                    .overview(owner, today)
                    .occurrences
                    .filter { it.occurrence.ruleId == rule.rule.id }
            val current = occurrences.single { it.occurrence.dueOn == today }
            val cancelled = occurrences.first { it.occurrence.dueOn > today }
            check(
                recurring.pay(
                    owner,
                    current.occurrence.id,
                    current.occurrence.version,
                    Money.of("40"),
                    today,
                    "mobile:planned:partial",
                ) is PlanChange.Applied,
            )
            check(
                recurring.cancelOccurrence(
                    owner,
                    cancelled.occurrence.id,
                    cancelled.occurrence.version,
                    today,
                ) is PlanChange.Applied,
            )

            val foreign = openBudget(FOREIGN_OWNER_ID)
            val foreignOwner = owner(foreign, FOREIGN_OWNER_ID)
            val foreignRule = createRule(foreignOwner, foreign.expenseCategory(), today, "999")
            val foreignOccurrence =
                recurring
                    .overview(foreignOwner, today)
                    .occurrences
                    .single { it.occurrence.ruleId == foreignRule.rule.id && it.occurrence.dueOn == today }

            mockMvc
                .perform(get("/api/v1/planned-expenses").header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isOk)
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.asOf").value(today.toString()))
                .andExpect(
                    jsonPath("$.throughInclusive")
                        .value(YearMonth.from(today.plusMonths(2)).atEndOfMonth().toString()),
                ).andExpect(jsonPath("$.items[*].id", hasItem(current.occurrence.id.toString())))
                .andExpect(jsonPath("$.items[*].id", not(hasItem(cancelled.occurrence.id.toString()))))
                .andExpect(jsonPath("$.items[*].id", not(hasItem(foreignOccurrence.occurrence.id.toString()))))
                .andExpect(jsonPath("$.items[0].category.name").value("Тестовый расход"))
                .andExpect(jsonPath("$.items[0].plannedAmount.amount").value("100.00"))
                .andExpect(jsonPath("$.items[0].paidAmount.amount").value("40.00"))
                .andExpect(jsonPath("$.items[0].remainingAmount.amount").value("60.00"))
                .andExpect(jsonPath("$.items[0].dueOn").value(today.toString()))
                .andExpect(jsonPath("$.items[0].overdue").value(false))
                .andExpect(jsonPath("$.items[0].status").value("SCHEDULED"))
        }

        @Test
        fun `requires bearer token and active membership`() {
            mockMvc
                .perform(get("/api/v1/planned-expenses"))
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
                .perform(get("/api/v1/planned-expenses").header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("access_denied"))
        }

        private fun createRule(
            owner: OwnedLedger,
            category: Category,
            startsOn: LocalDate,
            amount: String,
        ) = recurring
            .createRule(
                owner,
                RecurrenceFrequency.MONTHLY,
                startsOn.dayOfMonth,
                Money.of(amount),
                category.id,
                startsOn,
            ).let { result ->
                check(result is PlanChange.Applied)
                result.value
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

        private fun BudgetSetup.expenseCategory(): Category = categories.single { it.kind == CategoryKind.EXPENSE }

        companion object {
            private const val API_USER_ID = 991002L
            private const val OWNER_ID = 991001L
            private const val FOREIGN_OWNER_ID = 991003L
            private const val AUTHORIZATION = "Bearer synthetic-mobile-token-1234567890"
            private val API_ZONE = ZoneId.of("Asia/Almaty")
            private val ACCOUNTING_START = Instant.parse("2026-09-01T00:00:00Z")
        }
    }
