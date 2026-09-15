package com.firkatdavletov.financeconsultante.goals.domain

import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class SavingsGoalState {
    SAVING,
    ACHIEVED,
    OVERDUE,
}

data class SavingsPace(
    val daysRemaining: Long,
    val amountPerDay: Money,
) {
    init {
        require(daysRemaining > 0)
        require(amountPerDay.currency == Currency.KZT && amountPerDay.amount.signum() > 0)
    }
}

data class SavingsGoal(
    val id: UUID,
    val householdId: UUID,
    val memberId: UUID,
    val name: String,
    val targetAmount: Money,
    val deadline: LocalDate,
    val priority: Int,
    val allocatedAmount: Money = Money.of(BigDecimal.ZERO),
    val version: Long = 0,
    val createdAt: Instant,
    val updatedAt: Instant = createdAt,
) {
    init {
        require(name.isNotBlank() && name.length <= MAX_NAME_LENGTH)
        require(targetAmount.currency == Currency.KZT && targetAmount.amount.signum() > 0)
        require(allocatedAmount.currency == Currency.KZT && allocatedAmount.amount.signum() >= 0)
        require(allocatedAmount.amount <= targetAmount.amount)
        require(priority in MIN_PRIORITY..MAX_PRIORITY)
        require(version >= 0 && updatedAt >= createdAt)
    }

    val remainingAmount: Money
        get() = Money.of(targetAmount.amount - allocatedAmount.amount)

    fun state(asOf: LocalDate): SavingsGoalState =
        when {
            remainingAmount.amount.signum() == 0 -> SavingsGoalState.ACHIEVED
            deadline < asOf -> SavingsGoalState.OVERDUE
            else -> SavingsGoalState.SAVING
        }

    fun requiredPace(asOf: LocalDate): SavingsPace? {
        if (state(asOf) != SavingsGoalState.SAVING) return null
        val days = ChronoUnit.DAYS.between(asOf, deadline) + 1
        val daily = remainingAmount.amount.divide(BigDecimal(days), 2, RoundingMode.CEILING)
        return SavingsPace(days, Money.of(daily))
    }

    companion object {
        const val MIN_PRIORITY = 1
        const val MAX_PRIORITY = 5
        const val MAX_NAME_LENGTH = 100
    }
}

enum class GoalAllocationAction {
    RESERVED,
    RELEASED,
    SPENT,
}

data class GoalAllocation(
    val id: Long?,
    val householdId: UUID,
    val goalId: UUID,
    val action: GoalAllocationAction,
    val amount: Money,
    val source: String,
    val ledgerEntryId: UUID?,
    val createdAt: Instant,
) {
    init {
        require(amount.currency == Currency.KZT && amount.amount.signum() > 0)
        require(SOURCE_PATTERN.matches(source))
        require((action == GoalAllocationAction.SPENT) == (ledgerEntryId != null))
    }

    companion object {
        private val SOURCE_PATTERN = Regex("[A-Za-z0-9:_-]{1,128}")
    }
}

enum class ContributionPlanStatus {
    SCHEDULED,
    CANCELLED,
}

data class GoalContributionPlan(
    val id: UUID,
    val householdId: UUID,
    val goalId: UUID,
    val amount: Money,
    val dueOn: LocalDate,
    val status: ContributionPlanStatus = ContributionPlanStatus.SCHEDULED,
    val version: Long = 0,
    val createdAt: Instant,
    val updatedAt: Instant = createdAt,
) {
    init {
        require(amount.currency == Currency.KZT && amount.amount.signum() > 0)
        require(version >= 0 && updatedAt >= createdAt)
    }
}

data class SavingsGoalView(
    val goal: SavingsGoal,
    val plannedContributions: List<GoalContributionPlan>,
) {
    val plannedAmount: Money
        get() =
            Money.of(
                plannedContributions
                    .filter { it.status == ContributionPlanStatus.SCHEDULED }
                    .fold(BigDecimal.ZERO) { total, plan -> total + plan.amount.amount },
            )
}
