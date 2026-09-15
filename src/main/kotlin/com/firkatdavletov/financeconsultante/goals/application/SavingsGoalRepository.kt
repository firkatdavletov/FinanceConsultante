package com.firkatdavletov.financeconsultante.goals.application

import com.firkatdavletov.financeconsultante.goals.domain.GoalAllocation
import com.firkatdavletov.financeconsultante.goals.domain.GoalContributionPlan
import com.firkatdavletov.financeconsultante.goals.domain.SavingsGoal
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.shared.domain.Money
import java.time.Instant
import java.util.UUID

data class GoalBudgetSettings(
    val protectedReserve: Money,
)

@Suppress("TooManyFunctions") // The port keeps one cohesive transactional aggregate and its audit sources.
interface SavingsGoalRepository {
    /** Locks the account and household to serialize ledger and allocation decisions. */
    fun lockBudget(owner: OwnedLedger): GoalBudgetSettings?

    fun insertGoal(
        goal: SavingsGoal,
        source: String,
    ): Boolean

    fun findGoalBySource(
        householdId: UUID,
        source: String,
    ): SavingsGoal?

    fun findGoal(
        householdId: UUID,
        goalId: UUID,
        lock: Boolean = false,
    ): SavingsGoal?

    fun goals(
        householdId: UUID,
        limit: Int,
    ): List<SavingsGoal>

    fun totalAllocated(householdId: UUID): Money

    fun allocationBySource(
        householdId: UUID,
        source: String,
    ): GoalAllocation?

    fun changeAllocation(
        goal: SavingsGoal,
        expectedVersion: Long,
        allocation: GoalAllocation,
    ): Boolean

    fun contributionPlans(
        householdId: UUID,
        goalId: UUID,
    ): List<GoalContributionPlan>

    fun insertContributionPlan(
        plan: GoalContributionPlan,
        source: String,
    ): Boolean

    fun contributionPlanBySource(
        householdId: UUID,
        source: String,
    ): GoalContributionPlan?

    fun findContributionPlan(
        householdId: UUID,
        planId: UUID,
        lock: Boolean = false,
    ): GoalContributionPlan?

    fun cancelContributionPlan(
        planId: UUID,
        expectedVersion: Long,
        source: String,
        updatedAt: Instant,
    ): Boolean

    fun contributionPlanByCancelSource(
        householdId: UUID,
        source: String,
    ): GoalContributionPlan?
}
