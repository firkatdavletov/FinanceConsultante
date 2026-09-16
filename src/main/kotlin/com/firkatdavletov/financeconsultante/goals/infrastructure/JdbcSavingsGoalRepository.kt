package com.firkatdavletov.financeconsultante.goals.infrastructure

import com.firkatdavletov.financeconsultante.goals.application.GoalBudgetSettings
import com.firkatdavletov.financeconsultante.goals.application.SavingsGoalRepository
import com.firkatdavletov.financeconsultante.goals.domain.ContributionPlanStatus
import com.firkatdavletov.financeconsultante.goals.domain.GoalAllocation
import com.firkatdavletov.financeconsultante.goals.domain.GoalAllocationAction
import com.firkatdavletov.financeconsultante.goals.domain.GoalContributionPlan
import com.firkatdavletov.financeconsultante.goals.domain.SavingsGoal
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

@Repository
@Transactional(propagation = Propagation.MANDATORY)
@Suppress("TooManyFunctions") // Implements the cohesive goal aggregate port without exposing SQL elsewhere.
class JdbcSavingsGoalRepository(
    private val jdbc: JdbcClient,
) : SavingsGoalRepository {
    override fun lockBudget(owner: OwnedLedger): GoalBudgetSettings? =
        jdbc
            .sql(
                """
                SELECT h.cash_reserve FROM household h
                JOIN account a ON a.household_id = h.id
                JOIN member m ON m.household_id = h.id
                WHERE h.id = :household AND m.id = :member AND m.revoked_at IS NULL
                FOR UPDATE OF a, h
                """.trimIndent(),
            ).param("household", owner.householdId)
            .param("member", owner.memberId)
            .query { row, _ -> GoalBudgetSettings(Money.of(row.getBigDecimal("cash_reserve"))) }
            .optional()
            .orElse(null)

    override fun insertGoal(
        goal: SavingsGoal,
        source: String,
    ): Boolean =
        jdbc
            .sql(
                """
                INSERT INTO savings_goal(
                    id, household_id, member_id, name, target_amount, deadline, priority,
                    allocated_amount, version, source_key, created_at, updated_at
                ) VALUES (
                    :id, :household, :member, :name, :target, :deadline, :priority,
                    :allocated, :version, :source, :created, :updated
                ) ON CONFLICT (household_id, source_key) DO NOTHING
                """.trimIndent(),
            ).param("id", goal.id)
            .param("household", goal.householdId)
            .param("member", goal.memberId)
            .param("name", goal.name)
            .param("target", goal.targetAmount.amount)
            .param("deadline", goal.deadline)
            .param("priority", goal.priority)
            .param("allocated", goal.allocatedAmount.amount)
            .param("version", goal.version)
            .param("source", source)
            .param("created", offset(goal.createdAt))
            .param("updated", offset(goal.updatedAt))
            .update() == 1

    override fun findGoalBySource(
        householdId: UUID,
        source: String,
    ): SavingsGoal? = findGoal("household_id = :household AND source_key = :source", householdId, source = source)

    override fun findGoal(
        householdId: UUID,
        goalId: UUID,
        lock: Boolean,
    ): SavingsGoal? = findGoal("household_id = :household AND id = :id", householdId, goalId, lock)

    private fun findGoal(
        condition: String,
        householdId: UUID,
        goalId: UUID? = null,
        lock: Boolean = false,
        source: String? = null,
    ): SavingsGoal? {
        var query = jdbc.sql("SELECT * FROM savings_goal WHERE $condition${if (lock) " FOR UPDATE" else ""}")
        query = query.param("household", householdId)
        goalId?.let { query = query.param("id", it) }
        source?.let { query = query.param("source", it) }
        return query.query(goalMapper).optional().orElse(null)
    }

    override fun goals(
        householdId: UUID,
        limit: Int,
    ): List<SavingsGoal> =
        jdbc
            .sql(
                """
                SELECT * FROM savings_goal WHERE household_id = ?
                ORDER BY priority, deadline, created_at, id LIMIT ?
                """.trimIndent(),
            ).params(householdId, limit)
            .query(goalMapper)
            .list()

    override fun totalAllocated(householdId: UUID): Money =
        Money.of(
            jdbc
                .sql("SELECT COALESCE(SUM(allocated_amount), 0) FROM savings_goal WHERE household_id = ?")
                .param(householdId)
                .query(java.math.BigDecimal::class.java)
                .single(),
        )

    override fun allocationBySource(
        householdId: UUID,
        source: String,
    ): GoalAllocation? =
        jdbc
            .sql("SELECT * FROM savings_goal_allocation WHERE household_id = ? AND source_key = ?")
            .params(householdId, source)
            .query { row, _ ->
                GoalAllocation(
                    row.getLong("id"),
                    row.getObject("household_id", UUID::class.java),
                    row.getObject("goal_id", UUID::class.java),
                    GoalAllocationAction.valueOf(row.getString("action")),
                    Money.of(row.getBigDecimal("amount")),
                    row.getString("source_key"),
                    row.getObject("ledger_entry_id", UUID::class.java),
                    row.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                )
            }.optional()
            .orElse(null)

    override fun changeAllocation(
        goal: SavingsGoal,
        expectedVersion: Long,
        allocation: GoalAllocation,
    ): Boolean {
        val changed =
            jdbc
                .sql(
                    """
                    UPDATE savings_goal SET allocated_amount = :allocated, version = :version, updated_at = :updated
                    WHERE household_id = :household AND id = :id AND version = :expected
                    """.trimIndent(),
                ).param("allocated", goal.allocatedAmount.amount)
                .param("version", goal.version)
                .param("updated", offset(goal.updatedAt))
                .param("household", goal.householdId)
                .param("id", goal.id)
                .param("expected", expectedVersion)
                .update()
        if (changed != 1) return false
        jdbc
            .sql(
                """
                INSERT INTO savings_goal_allocation(
                    household_id, goal_id, action, amount, source_key, ledger_entry_id, created_at
                ) VALUES (:household, :goal, :action, :amount, :source, :entry, :created)
                """.trimIndent(),
            ).param("household", allocation.householdId)
            .param("goal", allocation.goalId)
            .param("action", allocation.action.name)
            .param("amount", allocation.amount.amount)
            .param("source", allocation.source)
            .param("entry", allocation.ledgerEntryId)
            .param("created", offset(allocation.createdAt))
            .update()
        jdbc
            .sql("UPDATE household SET settings_version = settings_version + 1 WHERE id = ?")
            .param(goal.householdId)
            .update()
        return true
    }

    override fun contributionPlans(
        householdId: UUID,
        goalId: UUID,
    ): List<GoalContributionPlan> =
        jdbc
            .sql(
                """
                SELECT * FROM goal_contribution_plan WHERE household_id = ? AND goal_id = ?
                ORDER BY due_on, created_at, id LIMIT 5
                """.trimIndent(),
            ).params(householdId, goalId)
            .query(planMapper)
            .list()

    override fun insertContributionPlan(
        plan: GoalContributionPlan,
        source: String,
    ): Boolean =
        jdbc
            .sql(
                """
                INSERT INTO goal_contribution_plan(
                    id, household_id, goal_id, amount, due_on, status, version,
                    source_key, created_at, updated_at
                ) VALUES (
                    :id, :household, :goal, :amount, :dueOn, :status, :version,
                    :source, :created, :updated
                ) ON CONFLICT (household_id, source_key) DO NOTHING
                """.trimIndent(),
            ).param("id", plan.id)
            .param("household", plan.householdId)
            .param("goal", plan.goalId)
            .param("amount", plan.amount.amount)
            .param("dueOn", plan.dueOn)
            .param("status", plan.status.name)
            .param("version", plan.version)
            .param("source", source)
            .param("created", offset(plan.createdAt))
            .param("updated", offset(plan.updatedAt))
            .update() == 1

    override fun contributionPlanBySource(
        householdId: UUID,
        source: String,
    ): GoalContributionPlan? =
        findPlan(
            "household_id = :household AND source_key = :source",
            householdId,
            source = source,
        )

    override fun findContributionPlan(
        householdId: UUID,
        planId: UUID,
        lock: Boolean,
    ): GoalContributionPlan? = findPlan("household_id = :household AND id = :id", householdId, planId, lock)

    override fun cancelContributionPlan(
        planId: UUID,
        expectedVersion: Long,
        source: String,
        updatedAt: Instant,
    ): Boolean =
        jdbc
            .sql(
                """
                UPDATE goal_contribution_plan
                SET status = 'CANCELLED', version = version + 1, cancel_source_key = :source, updated_at = :updated
                WHERE id = :id AND version = :version AND status = 'SCHEDULED'
                """.trimIndent(),
            ).param("source", source)
            .param("updated", offset(updatedAt))
            .param("id", planId)
            .param("version", expectedVersion)
            .update() == 1

    override fun contributionPlanByCancelSource(
        householdId: UUID,
        source: String,
    ): GoalContributionPlan? =
        findPlan("household_id = :household AND cancel_source_key = :source", householdId, source = source)

    private fun findPlan(
        condition: String,
        householdId: UUID,
        planId: UUID? = null,
        lock: Boolean = false,
        source: String? = null,
    ): GoalContributionPlan? {
        var query = jdbc.sql("SELECT * FROM goal_contribution_plan WHERE $condition${if (lock) " FOR UPDATE" else ""}")
        query = query.param("household", householdId)
        planId?.let { query = query.param("id", it) }
        source?.let { query = query.param("source", it) }
        return query.query(planMapper).optional().orElse(null)
    }

    private companion object {
        val goalMapper =
            RowMapper { row, _ ->
                SavingsGoal(
                    row.getObject("id", UUID::class.java),
                    row.getObject("household_id", UUID::class.java),
                    row.getObject("member_id", UUID::class.java),
                    row.getString("name"),
                    Money.of(row.getBigDecimal("target_amount")),
                    row.getObject("deadline", java.time.LocalDate::class.java),
                    row.getInt("priority"),
                    Money.of(row.getBigDecimal("allocated_amount")),
                    row.getLong("version"),
                    row.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                    row.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
                )
            }

        val planMapper =
            RowMapper { row, _ ->
                GoalContributionPlan(
                    row.getObject("id", UUID::class.java),
                    row.getObject("household_id", UUID::class.java),
                    row.getObject("goal_id", UUID::class.java),
                    Money.of(row.getBigDecimal("amount")),
                    row.getObject("due_on", java.time.LocalDate::class.java),
                    ContributionPlanStatus.valueOf(row.getString("status")),
                    row.getLong("version"),
                    row.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                    row.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
                )
            }

        fun offset(value: Instant): OffsetDateTime = value.atOffset(java.time.ZoneOffset.UTC)
    }
}
