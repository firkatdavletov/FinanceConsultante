package com.firkatdavletov.financeconsultante.mobileapi

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.ledger.application.AccountingService
import com.firkatdavletov.financeconsultante.ledger.domain.Account
import com.firkatdavletov.financeconsultante.ledger.domain.BudgetSetup
import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import com.firkatdavletov.financeconsultante.ledger.domain.Household
import com.firkatdavletov.financeconsultante.ledger.domain.Member
import com.firkatdavletov.financeconsultante.mobileapi.application.InvalidOfflineRequest
import com.firkatdavletov.financeconsultante.mobileapi.application.OfflineSyncService
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "app.mobile-api.enabled=true",
        "app.mobile-api.telegram-user-id=995083",
    ],
)
@Suppress("MagicNumber", "LongMethod", "LongParameterList")
class DashboardSyncApiTests
    @Autowired
    constructor(
        private val accounting: AccountingService,
        private val sync: OfflineSyncService,
        private val jdbc: JdbcTemplate,
        private val mvc: MockMvc,
    ) : PostgresIntegrationTest() {
        private var setup: BudgetSetup? = null

        @AfterEach fun cleanup() {
            setup?.let { budget ->
                listOf(
                    "recurring_expense_candidate_evidence",
                    "recurring_expense_candidate",
                    "savings_goal",
                    "recurring_occurrence",
                    "recurring_rule",
                    "ledger_entry",
                    "category",
                    "account",
                    "member",
                ).forEach {
                    jdbc.update("DELETE FROM $it WHERE household_id = ?", budget.household.id)
                }
                jdbc.update("DELETE FROM household WHERE id = ?", budget.household.id)
            }
        }

        @Test fun `v3 uses pinned journal inputs while old clients retain their supported kinds`() {
            val budget = budget()
            val first = sync.changes(USER, null, 1, 3)
            assertThat(first.hasMore).isTrue()
            jdbc.update("UPDATE household SET cash_reserve = 12.34 WHERE id = ?", budget.household.id)
            val initial = first.changes.toMutableList()
            var page = first
            while (page.hasMore) {
                page = sync.changes(USER, page.nextCursor, 1, 3)
                assertThat(page.snapshotRevision).isEqualTo(first.snapshotRevision)
                initial += page.changes
            }
            assertThat(
                initial.single { it.kind == "BUDGET_SETTINGS" }.budgetSettings?.openingBalance,
            ).isEqualTo("300.00")
            assertThat(
                initial.single { it.kind == "BUDGET_SETTINGS" }.budgetSettings?.protectedReserve,
            ).isEqualTo("0.00")
            val next = sync.changes(USER, page.nextCursor, 200, 3)
            assertThat(
                next.changes
                    .single()
                    .budgetSettings
                    ?.protectedReserve,
            ).isEqualTo("12.34")
            assertThat(sync.changes(USER, null, 200, 2).changes.map { it.kind }).containsOnly("CATEGORY")
            assertThat(sync.changes(USER, null, 200, 1).changes.map { it.kind }).containsOnly("CATEGORY")
            assertThatThrownBy {
                sync.changes(
                    USER,
                    first.nextCursor,
                    200,
                    2,
                )
            }.isInstanceOf(InvalidOfflineRequest::class.java)
            mvc.perform(get("/api/v3/sync/changes")).andExpect(status().isUnauthorized)
            mvc.perform(get("/api/v3/sync/changes").header("Authorization", AUTH)).andExpect(status().isUnauthorized)
            jdbc.update(
                "UPDATE member SET revoked_at = now() WHERE household_id = ? AND telegram_user_id = ?",
                budget.household.id,
                USER,
            )
            mvc.perform(get("/api/v3/sync/changes").header("Authorization", AUTH)).andExpect(status().isUnauthorized)
        }

        @Test fun `allocations and accepted candidate evidence are journaled including removals`() {
            val budget = budget()
            val goal = UUID.randomUUID()
            jdbc.update(
                """
                INSERT INTO savings_goal(id, household_id, member_id, name, target_amount, deadline,
                    priority, allocated_amount, source_key, created_at, updated_at)
                VALUES (?, ?, ?, 'Тест', 100, '2026-10-01', 1, 10.01, 'test:sync:goal', now(), now())
                """.trimIndent(),
                goal,
                budget.household.id,
                budget.owner.id,
            )
            assertThat(
                sync
                    .changes(USER, null, 200, 3)
                    .changes
                    .last()
                    .savingsGoal
                    ?.allocatedAmount,
            ).isEqualTo("10.01")
            jdbc.update("UPDATE savings_goal SET allocated_amount = 20.02 WHERE id = ?", goal)
            assertThat(
                sync
                    .changes(USER, null, 200, 3)
                    .changes
                    .last()
                    .savingsGoal
                    ?.allocatedAmount,
            ).isEqualTo("20.02")
            val entry = UUID.randomUUID()
            val rule = UUID.randomUUID()
            val candidate = UUID.randomUUID()
            jdbc.update(
                """
                INSERT INTO ledger_entry(id, household_id, account_id, accounting_started_at, category_id,
                    direction, original_amount, original_currency, amount_kzt, occurred_at, source_key, actor_id, created_at)
                VALUES (?, ?, ?, '2026-09-01T00:00:00Z', ?, 'EXPENSE', 19.01, 'KZT', 19.01,
                    '2026-09-02T00:00:00Z', 'test:sync:entry', ?, now())
                """.trimIndent(),
                entry,
                budget.household.id,
                budget.account.id,
                budget.categories.first().id,
                budget.owner.id,
            )
            jdbc.update(
                """
                INSERT INTO recurring_rule(id, household_id, member_id, category_id, frequency, schedule_value,
                    amount, starts_on, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'MONTHLY', 2, 19.01, '2026-09-01', now(), now())
                """.trimIndent(),
                rule,
                budget.household.id,
                budget.owner.id,
                budget.categories.first().id,
            )
            jdbc.update(
                """
                INSERT INTO recurring_expense_candidate(id, household_id, member_id, category_id,
                    normalized_description, frequency, schedule_value, suggested_amount, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'подписка', 'MONTHLY', 2, 19.01, now(), now())
                """.trimIndent(),
                candidate,
                budget.household.id,
                budget.owner.id,
                budget.categories.first().id,
            )
            jdbc.update(
                "INSERT INTO recurring_expense_candidate_evidence VALUES (?, ?, ?, now())",
                budget.household.id,
                candidate,
                entry,
            )
            jdbc.update(
                "UPDATE recurring_expense_candidate SET status = 'ACCEPTED', accepted_rule_id = ? WHERE id = ?",
                rule,
                candidate,
            )
            val accepted = sync.changes(USER, null, 200, 3).changes.last()
            assertThat(accepted.variableSpendingExclusion?.id).isEqualTo(entry.toString())
            assertThat(accepted.deleted).isFalse()
            val ledger =
                sync
                    .changes(USER, null, 200, 3)
                    .changes
                    .last { it.kind == "LEDGER" }
                    .operation!!
            assertThat(ledger.occurredAt).isEqualTo(Instant.parse("2026-09-02T00:00:00Z"))
            jdbc.update("DELETE FROM recurring_expense_candidate_evidence WHERE candidate_id = ?", candidate)
            assertThat(
                sync
                    .changes(USER, null, 200, 3)
                    .changes
                    .last()
                    .deleted,
            ).isTrue()
            jdbc.update("DELETE FROM savings_goal WHERE id = ?", goal)
            val removed = sync.changes(USER, null, 200, 3).changes.last()
            assertThat(removed.kind).isEqualTo("SAVINGS_GOAL")
            assertThat(removed.deleted).isTrue()
            assertThat(
                sync.changes(USER, null, 200, 2).changes.none {
                    it.kind in
                        setOf("SAVINGS_GOAL", "VARIABLE_SPENDING_EXCLUSION", "BUDGET_SETTINGS")
                },
            ).isTrue()
        }

        private fun budget(): BudgetSetup {
            val household = Household(UUID.randomUUID())
            return BudgetSetup(
                household,
                Member(UUID.randomUUID(), household.id, OWNER),
                Account(UUID.randomUUID(), household.id, Money.of("300"), Instant.parse("2026-09-01T00:00:00Z")),
                listOf(Category(UUID.randomUUID(), household.id, "Расход", CategoryKind.EXPENSE)),
            ).also {
                accounting.openBudget(it)
                jdbc.update(
                    "INSERT INTO member(id, household_id, telegram_user_id, role) VALUES (?, ?, ?, 'MEMBER')",
                    UUID.randomUUID(),
                    household.id,
                    USER,
                )
                setup = it
            }
        }

        companion object {
            private const val USER = 995083L
            private const val OWNER = 995084L
            private const val AUTH = "Bearer synthetic-dashboard-sync-token-12345"
        }
    }
