package com.firkatdavletov.financeconsultante.recurring

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.ledger.application.AccountingService
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.ledger.domain.Account
import com.firkatdavletov.financeconsultante.ledger.domain.BudgetSetup
import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import com.firkatdavletov.financeconsultante.ledger.domain.Household
import com.firkatdavletov.financeconsultante.ledger.domain.Member
import com.firkatdavletov.financeconsultante.mobileapi.application.InvalidPlannedExpenseRequest
import com.firkatdavletov.financeconsultante.mobileapi.application.PlannedExpenseMutationService
import com.firkatdavletov.financeconsultante.mobileapi.infrastructure.PlannedExpenseRuleResponse
import com.firkatdavletov.financeconsultante.recurring.application.PlanChange
import com.firkatdavletov.financeconsultante.recurring.application.RecurringPlanService
import com.firkatdavletov.financeconsultante.recurring.domain.OccurrenceStatus
import com.firkatdavletov.financeconsultante.recurring.domain.RecurrenceFrequency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Suppress("MagicNumber", "TooManyFunctions")
class OneTimeExpensePersistenceTests
    @Autowired
    constructor(
        private val service: RecurringPlanService,
        private val accounting: AccountingService,
        private val jdbc: JdbcTemplate,
        private val mutations: PlannedExpenseMutationService,
    ) : PostgresIntegrationTest() {
        private lateinit var setup: BudgetSetup
        private lateinit var owner: OwnedLedger
        private val today = LocalDate.parse("2026-09-06")

        @BeforeEach
        fun createBudget() {
            val household = Household(UUID.randomUUID())
            val start = Instant.parse("2026-08-31T18:00:00Z")
            setup =
                BudgetSetup(
                    household,
                    Member(UUID.randomUUID(), household.id, positiveTelegramId()),
                    Account(UUID.randomUUID(), household.id, Money.of("0"), start),
                    listOf(
                        Category(UUID.randomUUID(), household.id, "Связь", CategoryKind.EXPENSE),
                        Category(UUID.randomUUID(), household.id, "Зарплата", CategoryKind.INCOME),
                    ),
                )
            accounting.openBudget(setup)
            owner = OwnedLedger(household.id, setup.owner.id, setup.owner.telegramUserId, start)
        }

        @AfterEach
        fun removeBudget() {
            if (!::setup.isInitialized) return
            val household = setup.household.id
            listOf("recurring_occurrence_payment", "expected_income_match").forEach {
                jdbc.update("DELETE FROM $it WHERE household_id = ?", household)
            }
            jdbc.update(
                "DELETE FROM entry_revision WHERE household_id = ?",
                household,
            )
            jdbc.update("DELETE FROM ledger_entry WHERE household_id = ?", household)
            jdbc.update("DELETE FROM recurring_occurrence WHERE household_id = ?", household)
            jdbc.update("DELETE FROM recurring_rule WHERE household_id = ?", household)
            jdbc.update("DELETE FROM expected_income WHERE household_id = ?", household)
            jdbc.update("DELETE FROM category WHERE household_id = ?", household)
            jdbc.update("DELETE FROM account WHERE household_id = ?", household)
            jdbc.update("DELETE FROM member WHERE household_id = ?", household)
            jdbc.update("DELETE FROM household WHERE id = ?", household)
        }

        @Test
        fun `one-time API creation validates schedule and materializes a single expense`() {
            val user = setup.owner.telegramUserId
            listOf(1, 0).forEach { schedule ->
                assertThatThrownBy {
                    mutations.create(user, expenseCategory(), "100.00", "ONCE", schedule, today)
                }.isInstanceOf(InvalidPlannedExpenseRequest::class.java)
            }
            assertThatThrownBy {
                mutations.create(user, expenseCategory(), "100.00", "ONCE", null, today.minusDays(1))
            }.isInstanceOf(InvalidPlannedExpenseRequest::class.java)
            val view = mutations.create(user, expenseCategory(), "100.00", "ONCE", null, today)
            val response = PlannedExpenseRuleResponse.from(view)
            assertThat(response.frequency).isEqualTo("ONCE")
            assertThat(response.scheduleValue).isNull()
            assertThat(response.startsOn).isEqualTo(today)
            val expense = service.plannedExpenses(owner, today).expenses.single()
            assertThat(expense.occurrence.ruleId).isEqualTo(view.rule.id)
            assertThat(expense.occurrence.dueOn).isEqualTo(today)
            assertThat(expense.remainingAmount).isEqualTo(Money.of("100.00"))
        }

        @Test
        @Suppress("LongMethod") // Follow one persisted obligation through partial payment, closure and regeneration.
        fun `one-time payment and closure survive horizon extension and fresh reads`() {
            val rule =
                applied(
                    service.createRule(
                        owner,
                        RecurrenceFrequency.ONCE,
                        null,
                        Money.of("100"),
                        expenseCategory(),
                        today,
                    ),
                ).rule
            val original =
                service
                    .plannedExpenses(owner, today)
                    .expenses
                    .single()
                    .occurrence
            mutations.update(setup.owner.telegramUserId, rule.id, 0, "120.00")
            val updated = requireNotNull(service.occurrence(owner, original.id))
            assertThat(updated.occurrence.plannedAmount).isEqualTo(Money.of("120"))
            val payment =
                applied(
                    service.pay(
                        owner,
                        original.id,
                        updated.occurrence.version,
                        Money.of("40"),
                        today,
                        "synthetic:once:partial",
                    ),
                )
            service.plannedExpenses(owner, today.plusYears(5), today.plusYears(5))
            val partial = requireNotNull(service.occurrence(owner, original.id))
            assertThat(partial.paidAmount).isEqualTo(Money.of("40"))
            assertThat(partial.remainingAmount).isEqualTo(Money.of("80"))
            assertThat(partial.overdue(today.plusDays(1))).isTrue()
            mutations.update(setup.owner.telegramUserId, rule.id, 1, "150.00")
            assertThat(service.occurrence(owner, original.id)).isEqualTo(partial)
            val closed = mutations.close(setup.owner.telegramUserId, original.id, partial.occurrence.version)
            service.ensureOccurrences(owner, today.plusYears(10))
            assertThat(service.occurrence(owner, original.id)).isEqualTo(closed.item.expense)
            mutations.delete(setup.owner.telegramUserId, rule.id, 2)
            repeat(2) {
                service.generateAll()
                service.ensureOccurrences(owner, today.plusYears(10))
            }
            val reloaded = requireNotNull(service.occurrence(owner, original.id))
            assertThat(reloaded.occurrence.id).isEqualTo(original.id)
            assertThat(reloaded.occurrence.status).isEqualTo(OccurrenceStatus.PAID)
            assertThat(reloaded.occurrence.version).isEqualTo(closed.item.expense.occurrence.version)
            assertThat(reloaded.paidAmount).isEqualTo(Money.of("40"))
            assertThat(reloaded.remainingAmount).isEqualTo(Money.of("0"))
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM recurring_occurrence WHERE rule_id=?",
                    Long::class.java,
                    rule.id,
                ),
            ).isEqualTo(1)
            assertThat(
                jdbc.queryForObject(
                    "SELECT ledger_entry_id FROM recurring_occurrence_payment WHERE occurrence_id=?",
                    UUID::class.java,
                    original.id,
                ),
            ).isEqualTo(payment.entry.id)
        }

        @Test
        fun `fully paid one-time expense is never generated again`() {
            val rule = mutations.create(setup.owner.telegramUserId, expenseCategory(), "100.00", "ONCE", null, today)
            val original =
                service
                    .plannedExpenses(owner, today)
                    .expenses
                    .single()
                    .occurrence
            val paid =
                applied(
                    service.pay(
                        owner,
                        original.id,
                        original.version,
                        Money.of("100"),
                        today,
                        "synthetic:once:full",
                    ),
                )
            repeat(2) { service.ensureOccurrences(owner, today.plusYears(10)) }
            val reloaded = requireNotNull(service.occurrence(owner, original.id))
            assertThat(reloaded).isEqualTo(paid.occurrence)
            assertThat(reloaded.paidInFull).isTrue()
            assertThat(service.plannedExpenses(owner, today.plusYears(10)).expenses).isEmpty()
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM recurring_occurrence WHERE rule_id=?",
                    Long::class.java,
                    rule.rule.id,
                ),
            ).isEqualTo(1)
        }

        @Test
        fun `deactivation cancels future one-time expenses and retains overdue expenses`() {
            listOf(today.minusDays(1), today.plusDays(1)).forEach { dueOn ->
                val rule =
                    applied(
                        service.createRule(
                            owner,
                            RecurrenceFrequency.ONCE,
                            null,
                            Money.of("100"),
                            expenseCategory(),
                            dueOn,
                            authoredOn = today.minusDays(1),
                        ),
                    ).rule
                val expense = service.plannedExpenses(owner, today).expenses.single { it.occurrence.ruleId == rule.id }
                mutations.delete(setup.owner.telegramUserId, rule.id, 0)
                repeat(2) { service.ensureOccurrences(owner, today.plusYears(10)) }
                val reloaded = requireNotNull(service.occurrence(owner, expense.occurrence.id))
                assertThat(reloaded.occurrence.status).isEqualTo(
                    if (dueOn < today) OccurrenceStatus.SCHEDULED else OccurrenceStatus.CANCELLED,
                )
                assertThat(reloaded.overdue(today)).isEqualTo(dueOn < today)
                assertThat(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM recurring_occurrence WHERE rule_id=?",
                        Long::class.java,
                        rule.id,
                    ),
                ).isEqualTo(1)
            }
        }

        private fun expenseCategory(): UUID = setup.categories.single { it.kind == CategoryKind.EXPENSE }.id

        private fun positiveTelegramId(): Long = UUID.randomUUID().mostSignificantBits ushr 1

        private fun <T> applied(result: PlanChange<T>): T {
            assertThat(result).isInstanceOf(PlanChange.Applied::class.java)
            return (result as PlanChange.Applied).value
        }
    }
