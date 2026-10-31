package com.firkatdavletov.financeconsultante.analytics.infrastructure

import com.firkatdavletov.financeconsultante.analytics.application.BudgetForecastRepository
import com.firkatdavletov.financeconsultante.analytics.application.ForecastAccountSnapshot
import com.firkatdavletov.financeconsultante.analytics.domain.ForecastCashFlow
import com.firkatdavletov.financeconsultante.analytics.domain.ForecastCashFlowType
import com.firkatdavletov.financeconsultante.analytics.domain.ForecastContribution
import com.firkatdavletov.financeconsultante.analytics.domain.ForecastGoal
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
@Transactional(readOnly = true, propagation = Propagation.MANDATORY)
class JdbcBudgetForecastRepository(
    private val jdbc: JdbcClient,
) : BudgetForecastRepository {
    override fun account(
        owner: OwnedLedger,
        asOf: Instant,
    ): ForecastAccountSnapshot? =
        jdbc
            .sql(ACCOUNT)
            .param("household", owner.householdId)
            .param("member", owner.memberId)
            .param("owner", owner.ownerId)
            .param("started", owner.startedAt.atOffset(ZoneOffset.UTC))
            .param("asOf", asOf.atOffset(ZoneOffset.UTC))
            .query { row, _ ->
                ForecastAccountSnapshot(
                    Money.of(row.getBigDecimal("balance")),
                    Money.of(row.getBigDecimal("cash_reserve")),
                )
            }.optional()
            .orElse(null)

    override fun historicalVariableSpending(
        householdId: UUID,
        startInclusive: LocalDate,
        endExclusive: LocalDate,
    ): Money =
        Money.of(
            jdbc
                .sql(HISTORICAL_VARIABLE_SPENDING)
                .param("household", householdId)
                .param("start", startInclusive)
                .param("end", endExclusive)
                .query(java.math.BigDecimal::class.java)
                .single()
                .max(java.math.BigDecimal.ZERO),
        )

    override fun cashFlows(
        householdId: UUID,
        startInclusive: LocalDate,
        endExclusive: LocalDate,
    ): List<ForecastCashFlow> =
        jdbc
            .sql(CASH_FLOWS)
            .param("household", householdId)
            .param("start", startInclusive)
            .param("end", endExclusive)
            .query { row, _ ->
                ForecastCashFlow(
                    row.getObject("flow_date", LocalDate::class.java),
                    ForecastCashFlowType.valueOf(row.getString("flow_type")),
                    Money.of(row.getBigDecimal("amount")),
                    row.getObject("recurring_rule_id", UUID::class.java),
                    row.getBoolean("adjustable"),
                )
            }.list()

    override fun goals(
        householdId: UUID,
        startInclusive: LocalDate,
        throughInclusive: LocalDate,
    ): List<ForecastGoal> {
        val rows =
            jdbc
                .sql(GOALS)
                .param("household", householdId)
                .param("start", startInclusive)
                .param("through", throughInclusive)
                .query { row, _ ->
                    GoalRow(
                        row.getObject("id", UUID::class.java),
                        row.getString("name"),
                        Money.of(row.getBigDecimal("target_amount")),
                        Money.of(row.getBigDecimal("allocated_amount")),
                        row.getObject("deadline", LocalDate::class.java),
                        row.getInt("priority"),
                        row.getObject("plan_id", UUID::class.java),
                        row.getObject("due_on", LocalDate::class.java),
                        row.getBigDecimal("plan_amount")?.let(Money::of),
                    )
                }.list()
        return rows.groupBy(GoalRow::id).values.map { grouped ->
            val first = grouped.first()
            ForecastGoal(
                first.id,
                first.name,
                first.targetAmount,
                first.allocatedAmount,
                first.deadline,
                first.priority,
                grouped.mapNotNull { row ->
                    row.planId?.let { ForecastContribution(checkNotNull(row.dueOn), checkNotNull(row.planAmount)) }
                },
            )
        }
    }

    private data class GoalRow(
        val id: UUID,
        val name: String,
        val targetAmount: Money,
        val allocatedAmount: Money,
        val deadline: LocalDate,
        val priority: Int,
        val planId: UUID?,
        val dueOn: LocalDate?,
        val planAmount: Money?,
    )

    private companion object {
        val ACCOUNT =
            """
            SELECT a.opening_balance + COALESCE(SUM(
                CASE l.direction
                    WHEN 'INCOME' THEN l.amount_kzt
                    WHEN 'EXPENSE' THEN -l.amount_kzt
                    WHEN 'EXPENSE_REFUND' THEN l.amount_kzt
                END
            ) FILTER (WHERE l.status = 'POSTED'), 0) AS balance,
                h.cash_reserve
            FROM household h
            JOIN account a ON a.household_id = h.id
            JOIN member m ON m.household_id = h.id
            LEFT JOIN ledger_entry l ON l.household_id = h.id AND l.occurred_at <= :asOf
            WHERE h.id = :household AND m.id = :member AND m.telegram_user_id = :owner
                AND m.revoked_at IS NULL AND a.accounting_started_at = :started
            GROUP BY a.opening_balance, h.cash_reserve
            """.trimIndent()

        val HISTORICAL_VARIABLE_SPENDING =
            """
            SELECT COALESCE(SUM(
                CASE
                    WHEN l.direction = 'EXPENSE' AND NOT EXISTS (
                        SELECT 1 FROM recurring_occurrence_payment p
                        WHERE p.household_id = l.household_id AND p.ledger_entry_id = l.id
                    ) AND NOT EXISTS (
                        SELECT 1 FROM recurring_expense_candidate_evidence ce
                        JOIN recurring_expense_candidate c
                            ON c.household_id = ce.household_id AND c.id = ce.candidate_id
                        WHERE ce.household_id = l.household_id AND ce.ledger_entry_id = l.id
                            AND c.status = 'ACCEPTED'
                    ) THEN l.amount_kzt
                    WHEN l.direction = 'EXPENSE_REFUND' AND EXISTS (
                        SELECT 1 FROM ledger_entry original
                        WHERE original.household_id = l.household_id AND original.id = l.refund_of
                            AND NOT EXISTS (
                                SELECT 1 FROM recurring_occurrence_payment p
                                WHERE p.household_id = original.household_id AND p.ledger_entry_id = original.id
                            )
                            AND NOT EXISTS (
                                SELECT 1 FROM recurring_expense_candidate_evidence ce
                                JOIN recurring_expense_candidate c
                                    ON c.household_id = ce.household_id AND c.id = ce.candidate_id
                                WHERE ce.household_id = original.household_id
                                    AND ce.ledger_entry_id = original.id AND c.status = 'ACCEPTED'
                            )
                    ) THEN -l.amount_kzt
                    ELSE 0
                END
            ), 0)
            FROM ledger_entry l
            WHERE l.household_id = :household AND l.status = 'POSTED'
                AND l.occurred_on >= :start AND l.occurred_on < :end
            """.trimIndent()

        val CASH_FLOWS =
            """
            WITH obligations AS (
                SELECT GREATEST(o.due_on, :start::date) AS flow_date, 'OBLIGATION' AS flow_type,
                    GREATEST(o.planned_amount - COALESCE(SUM(operation_plan_contribution(l.household_id,l.id))
                        FILTER (WHERE l.status = 'POSTED'), 0), 0) AS amount,
                    o.rule_id AS recurring_rule_id,
                    o.due_on >= :start::date
                        AND COUNT(l.id) FILTER (WHERE l.status = 'POSTED') = 0 AS adjustable
                FROM recurring_occurrence o
                LEFT JOIN recurring_occurrence_payment p
                    ON p.household_id = o.household_id AND p.occurrence_id = o.id
                LEFT JOIN ledger_entry l
                    ON l.household_id = p.household_id AND l.id = p.ledger_entry_id
                WHERE o.household_id = :household AND o.status = 'SCHEDULED' AND o.due_on < :end
                GROUP BY o.id
            ), incomes AS (
                SELECT e.due_on AS flow_date, 'EXPECTED_INCOME' AS flow_type, GREATEST(e.planned_amount - COALESCE((SELECT SUM(operation_plan_contribution(m.household_id,m.ledger_entry_id)) FROM expected_income_match m WHERE m.household_id=e.household_id AND m.expected_income_id=e.id),0),0) AS amount,
                    NULL::uuid AS recurring_rule_id, FALSE AS adjustable
                FROM expected_income e
                WHERE e.household_id = :household AND e.status = 'SCHEDULED'
                    AND e.due_on >= :start AND e.due_on < :end

            )
            SELECT flow_date, flow_type, amount, recurring_rule_id, adjustable FROM obligations WHERE amount > 0
            UNION ALL
            SELECT flow_date, flow_type, amount, recurring_rule_id, adjustable FROM incomes WHERE amount > 0
            ORDER BY flow_date, flow_type
            """.trimIndent()

        val GOALS =
            """
            SELECT g.id, g.name, g.target_amount, g.allocated_amount, g.deadline, g.priority,
                p.id AS plan_id, p.due_on, p.amount AS plan_amount
            FROM savings_goal g
            LEFT JOIN goal_contribution_plan p ON p.household_id = g.household_id AND p.goal_id = g.id
                AND p.status = 'SCHEDULED' AND p.due_on >= :start AND p.due_on <= :through
            WHERE g.household_id = :household
            ORDER BY g.priority, g.deadline, g.id, p.due_on, p.id
            """.trimIndent()
    }
}
