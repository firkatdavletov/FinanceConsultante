package com.firkatdavletov.financeconsultante.recurring.application

import com.firkatdavletov.financeconsultante.ledger.domain.LedgerEntry
import com.firkatdavletov.financeconsultante.recurring.domain.ExpectedIncome
import com.firkatdavletov.financeconsultante.recurring.domain.ExpectedIncomeView
import com.firkatdavletov.financeconsultante.recurring.domain.RecurringOccurrence
import com.firkatdavletov.financeconsultante.recurring.domain.RecurringOccurrenceView
import com.firkatdavletov.financeconsultante.recurring.domain.RecurringRule
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class RecurringRuleView(
    val rule: RecurringRule,
    val categoryName: String,
)

@Suppress("TooManyFunctions") // One persistence boundary owns the consistency of rules, occurrences, and links.
interface RecurringPlanRepository {
    fun insertRule(rule: RecurringRule)

    fun rules(
        householdId: UUID,
        limit: Int,
    ): List<RecurringRuleView>

    fun activeRules(householdId: UUID? = null): List<RecurringRule>

    fun findRule(
        householdId: UUID,
        id: UUID,
        lock: Boolean = false,
    ): RecurringRule?

    fun ruleView(
        householdId: UUID,
        id: UUID,
    ): RecurringRuleView?

    fun updateRuleAmount(
        rule: RecurringRule,
        previousVersion: Long,
        effectiveOn: LocalDate,
    ): Boolean

    fun deactivateRule(
        rule: RecurringRule,
        previousVersion: Long,
        effectiveOn: LocalDate,
    ): Boolean

    fun insertOccurrences(
        rule: RecurringRule,
        dueDates: List<LocalDate>,
        now: Instant,
    ): Int

    fun occurrences(
        householdId: UUID,
        throughInclusive: LocalDate,
        limit: Int? = null,
    ): List<RecurringOccurrenceView>

    fun plannedExpenses(
        householdId: UUID,
        throughInclusive: LocalDate,
    ): List<RecurringOccurrenceView>

    fun matchableOccurrences(
        householdId: UUID,
        categoryId: UUID,
        closestTo: LocalDate,
        throughInclusive: LocalDate,
        limit: Int,
    ): List<RecurringOccurrenceView>

    fun findOccurrence(
        householdId: UUID,
        id: UUID,
        lock: Boolean = false,
    ): RecurringOccurrence?

    fun occurrenceView(
        householdId: UUID,
        id: UUID,
    ): RecurringOccurrenceView?

    fun advanceOccurrence(
        occurrenceId: UUID,
        previousVersion: Long,
        now: Instant,
    ): Boolean

    fun cancelOccurrence(
        occurrenceId: UUID,
        previousVersion: Long,
        now: Instant,
    ): Boolean

    fun markOccurrencePaid(
        occurrenceId: UUID,
        previousVersion: Long,
        now: Instant,
    ): Boolean

    fun linkPayment(
        householdId: UUID,
        occurrenceId: UUID,
        ledgerEntryId: UUID,
        now: Instant,
    ): Boolean

    fun insertExpectedIncome(expectedIncome: ExpectedIncome)

    fun expectedIncomes(
        householdId: UUID,
        throughInclusive: LocalDate? = null,
        limit: Int? = null,
    ): List<ExpectedIncomeView>

    fun findExpectedIncome(
        householdId: UUID,
        id: UUID,
        lock: Boolean = false,
    ): ExpectedIncome?

    fun expectedIncomeView(
        householdId: UUID,
        id: UUID,
    ): ExpectedIncomeView?

    /** Locks the closest unfulfilled plan; the caller must already hold the household account lock. */
    fun nearestExpectedIncome(
        householdId: UUID,
        categoryId: UUID,
        closestTo: LocalDate,
    ): ExpectedIncome?

    fun updateExpectedIncome(
        expectedIncome: ExpectedIncome,
        previousVersion: Long,
    ): Boolean

    fun cancelExpectedIncome(
        expectedIncomeId: UUID,
        previousVersion: Long,
        now: Instant,
    ): Boolean

    fun advanceExpectedIncome(
        expectedIncomeId: UUID,
        previousVersion: Long,
        now: Instant,
    ): Boolean

    fun linkExpectedIncome(
        householdId: UUID,
        expectedIncomeId: UUID,
        ledgerEntryId: UUID,
        now: Instant,
    )

    fun ledgerEntryAlreadyPlanned(
        householdId: UUID,
        ledgerEntryId: UUID,
    ): Boolean
}

data class RecurringOverview(
    val rules: List<RecurringRuleView>,
    val occurrences: List<RecurringOccurrenceView>,
)

data class PlannedExpenseSchedule(
    val asOf: LocalDate,
    val throughInclusive: LocalDate,
    val expenses: List<RecurringOccurrenceView>,
)

sealed interface PlanChange<out T> {
    data class Applied<T>(
        val value: T,
    ) : PlanChange<T>

    data class Rejected(
        val message: String,
    ) : PlanChange<Nothing>
}

data class PaymentReceipt(
    val occurrence: RecurringOccurrenceView,
    val entry: LedgerEntry,
    val entryRecorded: Boolean,
)

data class IncomeReceipt(
    val expectedIncome: ExpectedIncomeView,
    val entry: LedgerEntry,
    val entryRecorded: Boolean,
)
