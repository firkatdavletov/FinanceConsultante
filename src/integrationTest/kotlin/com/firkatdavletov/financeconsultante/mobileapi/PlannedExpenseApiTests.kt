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
@Suppress("MagicNumber", "TooManyFunctions") // Contract tests keep each mobile API behavior independently readable.
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
        @Suppress("LongMethod") // One response contract includes access scope, payments, recurrence, and filtering.
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
                .andExpect(jsonPath("$.items[0].rule.id").value(rule.rule.id.toString()))
                .andExpect(jsonPath("$.items[0].rule.frequency").value("MONTHLY"))
                .andExpect(jsonPath("$.items[0].rule.scheduleValue").value(today.dayOfMonth))
                .andExpect(jsonPath("$.items[0].rule.version").value(0))
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

        @Test
        @Suppress("LongMethod") // One scenario verifies the close response, balance, filtering, and safe retry.
        fun `owner closes last month expense when actual payment is below plan`() {
            val today = LocalDate.now(clock.withZone(API_ZONE))
            val accountingStart = Instant.parse("2026-08-01T00:00:00Z")
            val dueOn = LocalDate.parse("2026-08-05")
            val setup = openBudget(API_USER_ID, accountingStart)
            val owner = owner(setup, API_USER_ID)
            val rule = createRule(owner, setup.expenseCategory(), dueOn, "40500")
            val occurrence =
                recurring
                    .overview(owner, today)
                    .occurrences
                    .single { it.occurrence.ruleId == rule.rule.id && it.occurrence.dueOn == dueOn }
            val partial =
                recurring.pay(
                    owner,
                    occurrence.occurrence.id,
                    occurrence.occurrence.version,
                    Money.of("39000"),
                    dueOn,
                    "mobile:planned:rent",
                )
            check(partial is PlanChange.Applied)
            val partialVersion = partial.value.occurrence.occurrence.version
            val balanceBeforeClose = accounting.currentBalance(owner.householdId, owner.memberId).amount

            mockMvc
                .perform(
                    post("/api/v1/planned-expenses/{occurrenceId}/close", occurrence.occurrence.id)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"version":$partialVersion}"""),
                ).andExpect(status().isOk)
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.id").value(occurrence.occurrence.id.toString()))
                .andExpect(jsonPath("$.plannedAmount.amount").value("40500.00"))
                .andExpect(jsonPath("$.paidAmount.amount").value("39000.00"))
                .andExpect(jsonPath("$.remainingAmount.amount").value("0.00"))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.version").value(partialVersion + 1))

            assertThat(
                accounting.currentBalance(owner.householdId, owner.memberId).amount,
            ).isEqualTo(balanceBeforeClose)
            assertThat(
                jdbc.queryForObject(
                    "SELECT status FROM recurring_occurrence WHERE household_id = ? AND id = ?",
                    String::class.java,
                    owner.householdId,
                    occurrence.occurrence.id,
                ),
            ).isEqualTo("PAID")

            mockMvc
                .perform(get("/api/v1/planned-expenses").header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items[*].id", not(hasItem(occurrence.occurrence.id.toString()))))

            mockMvc
                .perform(
                    post("/api/v1/planned-expenses/{occurrenceId}/close", occurrence.occurrence.id)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"version":$partialVersion}"""),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("planned_expense_conflict"))
            assertThat(
                accounting.currentBalance(owner.householdId, owner.memberId).amount,
            ).isEqualTo(balanceBeforeClose)
        }

        @Test
        fun `owner creates edits and deletes a planned expense rule`() {
            val today = LocalDate.now(clock.withZone(API_ZONE))
            val setup = openBudget(API_USER_ID)
            val category = setup.expenseCategory()

            mockMvc
                .perform(
                    post("/api/v1/planned-expenses")
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {
                              "categoryId": "${category.id}",
                              "amount": "100.00",
                              "frequency": "MONTHLY",
                              "scheduleValue": ${today.dayOfMonth},
                              "startsOn": "$today"
                            }
                            """.trimIndent(),
                        ),
                ).andExpect(status().isCreated)
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.category.id").value(category.id.toString()))
                .andExpect(jsonPath("$.plannedAmount.amount").value("100.00"))
                .andExpect(jsonPath("$.frequency").value("MONTHLY"))
                .andExpect(jsonPath("$.version").value(0))

            val ruleId =
                checkNotNull(
                    jdbc.queryForObject(
                        "SELECT id FROM recurring_rule WHERE household_id = ?",
                        UUID::class.java,
                        setup.household.id,
                    ),
                )

            mockMvc
                .perform(
                    patch("/api/v1/planned-expenses/{ruleId}", ruleId)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"version":0,"amount":"125.50"}"""),
                ).andExpect(status().isOk)
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.id").value(ruleId.toString()))
                .andExpect(jsonPath("$.plannedAmount.amount").value("125.50"))
                .andExpect(jsonPath("$.version").value(1))

            mockMvc
                .perform(
                    delete("/api/v1/planned-expenses/{ruleId}", ruleId)
                        .queryParam("version", "1")
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION),
                ).andExpect(status().isNoContent)
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))

            mockMvc
                .perform(get("/api/v1/planned-expenses").header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(0))
        }

        @Test
        @Suppress("LongMethod") // Mutation guards share one prepared owner budget and HTTP contract.
        fun `planned expense mutations require owner valid input and current version`() {
            val today = LocalDate.now(clock.withZone(API_ZONE))
            val memberBudget = openBudget(OWNER_ID)
            addMember(memberBudget, API_USER_ID)

            mockMvc
                .perform(
                    post("/api/v1/planned-expenses")
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest(memberBudget.expenseCategory(), today, "100")),
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
                    post("/api/v1/planned-expenses")
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest(ownerBudget.expenseCategory(), today, "1.001")),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("invalid_planned_expense"))

            val rule = createRule(owner(ownerBudget, API_USER_ID), ownerBudget.expenseCategory(), today, "100")
            val occurrence =
                recurring
                    .overview(owner(ownerBudget, API_USER_ID), today)
                    .occurrences
                    .single { it.occurrence.ruleId == rule.rule.id && it.occurrence.dueOn == today }
            mockMvc
                .perform(
                    post("/api/v1/planned-expenses/{occurrenceId}/close", occurrence.occurrence.id)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"version":0}"""),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("planned_expense_conflict"))

            mockMvc
                .perform(
                    post("/api/v1/planned-expenses/{occurrenceId}/close", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"version":0}"""),
                ).andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("planned_expense_not_found"))

            mockMvc
                .perform(
                    patch("/api/v1/planned-expenses/{ruleId}", rule.rule.id)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"version":7,"amount":"200"}"""),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("planned_expense_conflict"))

            mockMvc
                .perform(
                    patch("/api/v1/planned-expenses/{ruleId}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"version":0,"amount":"200"}"""),
                ).andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("planned_expense_not_found"))
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

        private fun openBudget(
            telegramUserId: Long,
            accountingStart: Instant = ACCOUNTING_START,
        ): BudgetSetup {
            val household = Household(UUID.randomUUID())
            val setup =
                BudgetSetup(
                    household,
                    Member(UUID.randomUUID(), household.id, telegramUserId),
                    Account(UUID.randomUUID(), household.id, Money.of("0"), accountingStart),
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
        ) = OwnedLedger(setup.household.id, setup.owner.id, telegramUserId, setup.account.accountingStartedAt)

        private fun createRequest(
            category: Category,
            startsOn: LocalDate,
            amount: String,
        ) = """
            {
              "categoryId": "${category.id}",
              "amount": "$amount",
              "frequency": "MONTHLY",
              "scheduleValue": ${startsOn.dayOfMonth},
              "startsOn": "$startsOn"
            }
            """.trimIndent()

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
