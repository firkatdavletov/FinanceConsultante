package com.firkatdavletov.financeconsultante.budget.application

import com.firkatdavletov.financeconsultante.budget.domain.BudgetCalculationInput
import com.firkatdavletov.financeconsultante.budget.domain.BudgetCalculator
import com.firkatdavletov.financeconsultante.budget.domain.BudgetEstimate
import com.firkatdavletov.financeconsultante.budget.domain.DailyBudgetSnapshot
import com.firkatdavletov.financeconsultante.budget.domain.DailyBudgetView
import com.firkatdavletov.financeconsultante.goals.application.SavingsGoalService
import com.firkatdavletov.financeconsultante.ledger.application.AccountingService
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.recurring.application.RecurringPlanService
import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import com.firkatdavletov.financeconsultante.telegram.domain.ACCOUNTING_ZONE
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Service
@Transactional(propagation = Propagation.MANDATORY)
class BudgetReportService(
    private val repository: BudgetReportRepository,
    private val accounting: AccountingService,
    private val recurring: RecurringPlanService,
    private val goals: SavingsGoalService,
    private val clock: Clock,
) {
    @Suppress("ReturnCount") // Snapshot creation and invalidation are distinct terminal outcomes.
    fun today(
        owner: OwnedLedger,
        replaceSnapshot: Boolean = false,
    ): DailyBudgetView {
        val asOf = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val date = asOf.atZone(ACCOUNTING_ZONE).toLocalDate()
        val estimate = estimate(owner, date)
        val current = checkNotNull(estimate.freeAmount)
        val existing = repository.snapshot(owner.householdId, date)
        if (existing == null || replaceSnapshot) {
            val snapshot = snapshot(owner, date, asOf, estimate, existing != null)
            repository.save(snapshot)
            return fresh(snapshot, estimate)
        }

        val spent = repository.ordinarySpendingAfter(existing)
        val expectedFree = existing.freeAmount - spent
        val changed =
            existing.settingsVersion != repository.settings(owner).version ||
                repository.hasInvalidatingLedgerChanges(existing) || current != expectedFree
        if (changed) {
            val snapshot = snapshot(owner, date, asOf, estimate, recalculated = true)
            repository.save(snapshot)
            return fresh(snapshot, estimate)
        }

        val remaining =
            minimumNonNegative(
                existing.originalDailyLimit.amount.subtract(spent.amount),
                current.amount,
            )
        return DailyBudgetView(existing, estimate, spent, Money.of(remaining, Currency.KZT))
    }

    private fun estimate(
        owner: OwnedLedger,
        date: LocalDate,
    ): BudgetEstimate {
        val settings = repository.settings(owner, lock = true)
        val monthEnd = date.withDayOfMonth(date.lengthOfMonth())
        val obligations = recurring.remainingObligations(owner, monthEnd)
        val balance = accounting.currentBalance(owner.householdId, owner.memberId).amount
        return BudgetCalculator.calculate(
            BudgetCalculationInput(
                balance,
                obligations,
                settings.protectedReserve,
                goals.totalAllocated(owner),
                date,
            ),
        )
    }

    private fun snapshot(
        owner: OwnedLedger,
        date: LocalDate,
        asOf: Instant,
        estimate: BudgetEstimate,
        recalculated: Boolean,
    ): DailyBudgetSnapshot {
        val settings = repository.settings(owner)
        return DailyBudgetSnapshot(
            owner.householdId,
            date,
            asOf,
            checkNotNull(estimate.balance),
            estimate.remainingObligations,
            estimate.protectedReserve,
            estimate.goalReserve,
            checkNotNull(estimate.freeAmount),
            estimate.daysRemaining,
            checkNotNull(estimate.dailyLimit),
            repository.ledgerRevision(owner.householdId),
            settings.version,
            recalculated,
        )
    }

    private fun fresh(
        snapshot: DailyBudgetSnapshot,
        estimate: BudgetEstimate,
    ): DailyBudgetView =
        DailyBudgetView(
            snapshot,
            estimate,
            Money.of(BigDecimal.ZERO),
            snapshot.originalDailyLimit,
        )

    private fun minimumNonNegative(
        first: BigDecimal,
        second: BigDecimal,
    ): BigDecimal = first.max(BigDecimal.ZERO).min(second.max(BigDecimal.ZERO))
}
