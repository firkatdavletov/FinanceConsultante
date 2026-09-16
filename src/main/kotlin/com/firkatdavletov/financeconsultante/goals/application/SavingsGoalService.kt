package com.firkatdavletov.financeconsultante.goals.application

import com.firkatdavletov.financeconsultante.goals.domain.ContributionPlanStatus
import com.firkatdavletov.financeconsultante.goals.domain.GoalAllocation
import com.firkatdavletov.financeconsultante.goals.domain.GoalAllocationAction
import com.firkatdavletov.financeconsultante.goals.domain.GoalContributionPlan
import com.firkatdavletov.financeconsultante.goals.domain.SavingsGoal
import com.firkatdavletov.financeconsultante.goals.domain.SavingsGoalState
import com.firkatdavletov.financeconsultante.goals.domain.SavingsGoalView
import com.firkatdavletov.financeconsultante.ledger.application.AccountingService
import com.firkatdavletov.financeconsultante.ledger.application.BudgetRepository
import com.firkatdavletov.financeconsultante.ledger.application.LedgerRepository
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.ledger.application.RecordEntry
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.ledger.domain.LedgerEntry
import com.firkatdavletov.financeconsultante.recurring.application.RecurringPlanService
import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.UUID

sealed interface GoalChange<out T> {
    data class Applied<T>(
        val value: T,
        val changed: Boolean = true,
    ) : GoalChange<T>

    data class Rejected(
        val message: String,
    ) : GoalChange<Nothing>
}

data class SavingsOverview(
    val goals: List<SavingsGoalView>,
    val totalAllocated: Money,
    val asOf: LocalDate,
)

data class GoalSpendReceipt(
    val goal: SavingsGoal,
    val entry: LedgerEntry,
    val recorded: Boolean,
)

@Service
@Transactional
@Suppress("TooManyFunctions", "ReturnCount") // Each guard prevents an invalid goal or ledger mutation.
class SavingsGoalService(
    private val goals: SavingsGoalRepository,
    private val budgets: BudgetRepository,
    private val ledger: LedgerRepository,
    private val accounting: AccountingService,
    private val recurring: RecurringPlanService,
    private val clock: Clock,
) {
    fun overview(
        owner: OwnedLedger,
        asOf: LocalDate,
    ): SavingsOverview {
        requireNotNull(goals.lockBudget(owner)) { "Budget access denied" }
        val views =
            goals.goals(owner.householdId, LIST_LIMIT).map {
                SavingsGoalView(it, goals.contributionPlans(owner.householdId, it.id))
            }
        return SavingsOverview(views, goals.totalAllocated(owner.householdId), asOf)
    }

    fun totalAllocated(owner: OwnedLedger): Money = goals.totalAllocated(owner.householdId)

    fun create(
        owner: OwnedLedger,
        name: String,
        targetAmount: Money,
        deadline: LocalDate,
        priority: Int,
        asOf: LocalDate,
        source: String,
    ): GoalChange<SavingsGoal> {
        if (!owner.canManageHousehold) return ownerOnly()
        requireNotNull(goals.lockBudget(owner)) { "Budget access denied" }
        goals.findGoalBySource(owner.householdId, source)?.let { return GoalChange.Applied(it, changed = false) }
        amountError(targetAmount)?.let { return GoalChange.Rejected(it) }
        val normalizedName = name.trim().replace(Regex("\\s+"), " ")
        if (normalizedName.isEmpty() || normalizedName.length > SavingsGoal.MAX_NAME_LENGTH) {
            return GoalChange.Rejected("Название цели должно содержать от 1 до 100 символов.")
        }
        if (deadline < asOf) return GoalChange.Rejected("Срок новой цели не может быть в прошлом.")
        if (priority !in SavingsGoal.MIN_PRIORITY..SavingsGoal.MAX_PRIORITY) {
            return GoalChange.Rejected("Приоритет должен быть от 1 (самый высокий) до 5.")
        }
        val now = now()
        val goal =
            SavingsGoal(
                UUID.randomUUID(),
                owner.householdId,
                owner.memberId,
                normalizedName,
                targetAmount,
                deadline,
                priority,
                createdAt = now,
            )
        check(goals.insertGoal(goal, source)) { "Goal source changed while budget was locked" }
        return GoalChange.Applied(goal)
    }

    fun reserve(
        owner: OwnedLedger,
        goalId: UUID,
        version: Long,
        amount: Money,
        source: String,
    ): GoalChange<SavingsGoal> {
        if (!owner.canManageHousehold) return ownerOnly()
        val settings = requireNotNull(goals.lockBudget(owner)) { "Budget access denied" }
        allocationReplay(owner, goalId, source)?.let { return it }
        amountError(amount)?.let { return GoalChange.Rejected(it) }
        val current = currentGoal(owner, goalId, version) ?: return GoalChange.Rejected(STALE_GOAL)
        if (amount.amount > current.remainingAmount.amount) {
            return GoalChange.Rejected(
                "До суммы цели осталось ${current.remainingAmount.amount.toPlainString()} KZT.",
            )
        }
        val available = available(owner, settings)
        if (amount.amount > available.amount) {
            return GoalChange.Rejected(
                "Недостаточно свободных средств: доступно ${available.amount.toPlainString()} KZT после B − R − E − G.",
            )
        }
        val updated =
            current.copy(
                allocatedAmount = current.allocatedAmount + amount,
                version = version + 1,
                updatedAt = now(),
            )
        val allocation = allocation(owner, goalId, GoalAllocationAction.RESERVED, amount, source)
        check(goals.changeAllocation(updated, version, allocation)) { "Goal changed while budget was locked" }
        return GoalChange.Applied(updated)
    }

    fun release(
        owner: OwnedLedger,
        goalId: UUID,
        version: Long,
        amount: Money,
        source: String,
    ): GoalChange<SavingsGoal> {
        if (!owner.canManageHousehold) return ownerOnly()
        requireNotNull(goals.lockBudget(owner)) { "Budget access denied" }
        allocationReplay(owner, goalId, source)?.let { return it }
        amountError(amount)?.let { return GoalChange.Rejected(it) }
        val current = currentGoal(owner, goalId, version) ?: return GoalChange.Rejected(STALE_GOAL)
        if (amount.amount > current.allocatedAmount.amount) {
            return GoalChange.Rejected(
                "В цели выделено только ${current.allocatedAmount.amount.toPlainString()} KZT.",
            )
        }
        val updated =
            current.copy(
                allocatedAmount = current.allocatedAmount - amount,
                version = version + 1,
                updatedAt = now(),
            )
        val allocation = allocation(owner, goalId, GoalAllocationAction.RELEASED, amount, source)
        check(goals.changeAllocation(updated, version, allocation)) { "Goal changed while budget was locked" }
        return GoalChange.Applied(updated)
    }

    @Suppress("LongParameterList") // One command atomically records a goal-funded expense and allocation change.
    fun spend(
        owner: OwnedLedger,
        goalId: UUID,
        version: Long,
        amount: Money,
        categoryId: UUID,
        spentOn: LocalDate,
        source: String,
    ): GoalChange<GoalSpendReceipt> {
        if (!owner.canManageHousehold) return ownerOnly()
        requireNotNull(goals.lockBudget(owner)) { "Budget access denied" }
        spendReplay(owner, goalId, source)?.let { return it }
        amountError(amount)?.let { return GoalChange.Rejected(it) }
        factError(owner, categoryId, spentOn)?.let { return GoalChange.Rejected(it) }
        val current = currentGoal(owner, goalId, version) ?: return GoalChange.Rejected(STALE_GOAL)
        if (amount.amount > current.allocatedAmount.amount) {
            return GoalChange.Rejected(
                "В цели выделено только ${current.allocatedAmount.amount.toPlainString()} KZT.",
            )
        }
        val recorded =
            accounting.record(
                RecordEntry(
                    owner.householdId,
                    owner.memberId,
                    categoryId,
                    Direction.EXPENSE,
                    amount,
                    spentOn.atStartOfDay(ACCOUNTING_ZONE).toInstant(),
                    source,
                ),
            )
        if (!recorded.recorded) return GoalChange.Rejected("Этот источник уже использован другой проводкой.")
        val updated =
            current.copy(
                allocatedAmount = current.allocatedAmount - amount,
                version = version + 1,
                updatedAt = now(),
            )
        val allocation =
            allocation(
                owner,
                goalId,
                GoalAllocationAction.SPENT,
                amount,
                source,
                recorded.entry.id,
            )
        check(goals.changeAllocation(updated, version, allocation)) { "Goal changed while budget was locked" }
        return GoalChange.Applied(GoalSpendReceipt(updated, recorded.entry, true))
    }

    fun planContribution(
        owner: OwnedLedger,
        goalId: UUID,
        goalVersion: Long,
        amount: Money,
        dueOn: LocalDate,
        asOf: LocalDate,
        source: String,
    ): GoalChange<GoalContributionPlan> {
        if (!owner.canManageHousehold) return ownerOnly()
        requireNotNull(goals.lockBudget(owner)) { "Budget access denied" }
        goals.contributionPlanBySource(owner.householdId, source)?.let {
            return GoalChange.Applied(it, changed = false)
        }
        amountError(amount)?.let { return GoalChange.Rejected(it) }
        val goal = currentGoal(owner, goalId, goalVersion) ?: return GoalChange.Rejected(STALE_GOAL)
        if (goal.state(asOf) == SavingsGoalState.ACHIEVED) {
            return GoalChange.Rejected("Цель уже достигнута; будущий взнос не нужен.")
        }
        if (dueOn < asOf || dueOn > goal.deadline) {
            return GoalChange.Rejected("Дата взноса должна быть от сегодня до срока цели ${goal.deadline}.")
        }
        val now = now()
        val plan = GoalContributionPlan(UUID.randomUUID(), owner.householdId, goalId, amount, dueOn, createdAt = now)
        check(goals.insertContributionPlan(plan, source)) { "Contribution source changed while budget was locked" }
        return GoalChange.Applied(plan)
    }

    fun cancelContribution(
        owner: OwnedLedger,
        planId: UUID,
        version: Long,
        source: String,
    ): GoalChange<GoalContributionPlan> {
        if (!owner.canManageHousehold) return ownerOnly()
        requireNotNull(goals.lockBudget(owner)) { "Budget access denied" }
        goals.contributionPlanByCancelSource(owner.householdId, source)?.let {
            return GoalChange.Applied(it, changed = false)
        }
        val plan =
            goals.findContributionPlan(owner.householdId, planId, lock = true)
                ?: return GoalChange.Rejected(STALE_PLAN)
        if (plan.version != version || plan.status != ContributionPlanStatus.SCHEDULED) {
            return GoalChange.Rejected(STALE_PLAN)
        }
        check(goals.cancelContributionPlan(planId, version, source, now())) { "Contribution changed while locked" }
        return GoalChange.Applied(checkNotNull(goals.findContributionPlan(owner.householdId, planId)))
    }

    private fun available(
        owner: OwnedLedger,
        settings: GoalBudgetSettings,
    ): Money {
        val today = LocalDate.now(clock.withZone(ACCOUNTING_ZONE))
        val obligations = recurring.remainingObligations(owner, YearMonth.from(today).atEndOfMonth())
        val balance = accounting.currentBalance(owner.householdId, owner.memberId).amount
        val allocated = goals.totalAllocated(owner.householdId)
        val free = balance - obligations - settings.protectedReserve - allocated
        return Money.of(free.amount.max(BigDecimal.ZERO))
    }

    private fun currentGoal(
        owner: OwnedLedger,
        goalId: UUID,
        version: Long,
    ): SavingsGoal? = goals.findGoal(owner.householdId, goalId, lock = true)?.takeIf { it.version == version }

    private fun allocationReplay(
        owner: OwnedLedger,
        goalId: UUID,
        source: String,
    ): GoalChange<SavingsGoal>? {
        val allocation = goals.allocationBySource(owner.householdId, source) ?: return null
        return if (allocation.goalId == goalId) {
            GoalChange.Applied(checkNotNull(goals.findGoal(owner.householdId, goalId)), changed = false)
        } else {
            GoalChange.Rejected("Этот источник уже использован для другой цели.")
        }
    }

    private fun spendReplay(
        owner: OwnedLedger,
        goalId: UUID,
        source: String,
    ): GoalChange<GoalSpendReceipt>? {
        val allocation = goals.allocationBySource(owner.householdId, source) ?: return null
        if (allocation.goalId != goalId || allocation.action != GoalAllocationAction.SPENT) {
            return GoalChange.Rejected("Этот источник уже использован для другого действия.")
        }
        val entry = checkNotNull(allocation.ledgerEntryId?.let { ledger.find(owner.householdId, it) })
        val goal = checkNotNull(goals.findGoal(owner.householdId, goalId))
        return GoalChange.Applied(GoalSpendReceipt(goal, entry, recorded = false), changed = false)
    }

    private fun allocation(
        owner: OwnedLedger,
        goalId: UUID,
        action: GoalAllocationAction,
        amount: Money,
        source: String,
        ledgerEntryId: UUID? = null,
    ): GoalAllocation =
        GoalAllocation(
            null,
            owner.householdId,
            goalId,
            action,
            amount,
            source,
            ledgerEntryId,
            now(),
        )

    private fun factError(
        owner: OwnedLedger,
        categoryId: UUID,
        spentOn: LocalDate,
    ): String? {
        val today = LocalDate.now(clock.withZone(ACCOUNTING_ZONE))
        if (spentOn < owner.startedAt.atZone(ACCOUNTING_ZONE).toLocalDate()) return "Дата раньше начала учёта."
        if (spentOn > today) return "Будущая дата не является фактом расхода."
        val category = budgets.findCategory(owner.householdId, categoryId)
        return if (category == null || category.archived || category.kind != CategoryKind.EXPENSE) {
            "Нужна активная категория расхода из /categories."
        } else {
            null
        }
    }

    private fun amountError(amount: Money): String? =
        when {
            amount.currency != Currency.KZT -> "Цели, резервы и планы взносов задаются в KZT."
            amount.amount.signum() <= 0 -> "Сумма должна быть больше нуля."
            else -> null
        }

    private fun ownerOnly(): GoalChange.Rejected = GoalChange.Rejected("Общие цели меняет только OWNER.")

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    companion object {
        private val ACCOUNTING_ZONE = java.time.ZoneId.of("Asia/Almaty")
        private const val LIST_LIMIT = 10
        private const val STALE_GOAL = "Цель изменилась или недоступна. Откройте /goals."
        private const val STALE_PLAN = "План взноса изменился или недоступен. Откройте /goals."
    }
}
