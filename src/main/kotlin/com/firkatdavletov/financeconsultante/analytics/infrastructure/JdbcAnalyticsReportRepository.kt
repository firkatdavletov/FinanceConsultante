package com.firkatdavletov.financeconsultante.analytics.infrastructure

import com.firkatdavletov.financeconsultante.analytics.application.AnalyticsReportRepository
import com.firkatdavletov.financeconsultante.analytics.domain.CategoryAnalytics
import com.firkatdavletov.financeconsultante.analytics.domain.PeriodAnalytics
import com.firkatdavletov.financeconsultante.analytics.domain.ReportRange
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

@Repository
@Transactional(readOnly = true, propagation = Propagation.MANDATORY)
class JdbcAnalyticsReportRepository(
    private val jdbc: JdbcClient,
) : AnalyticsReportRepository {
    override fun owns(owner: OwnedLedger): Boolean =
        jdbc
            .sql(
                """
                SELECT EXISTS (
                    SELECT 1 FROM member m JOIN account a ON a.household_id = m.household_id
                    WHERE m.household_id = :household AND m.id = :member AND m.telegram_user_id = :owner
                        AND m.role = 'OWNER' AND a.accounting_started_at = :started
                )
                """.trimIndent(),
            ).param("household", owner.householdId)
            .param("member", owner.memberId)
            .param("owner", owner.ownerId)
            .param("started", owner.startedAt.atOffset(java.time.ZoneOffset.UTC))
            .query(Boolean::class.java)
            .single()

    override fun aggregate(
        owner: OwnedLedger,
        range: ReportRange,
    ): PeriodAnalytics {
        val rows =
            jdbc
                .sql(
                    """
                    SELECT c.id, c.name, c.kind,
                        COALESCE(SUM(l.amount_kzt) FILTER (WHERE l.direction = 'INCOME'), 0) AS income,
                        COALESCE(SUM(l.amount_kzt) FILTER (WHERE l.direction = 'EXPENSE'), 0) AS expenses,
                        COALESCE(SUM(l.amount_kzt) FILTER (WHERE l.direction = 'EXPENSE_REFUND'), 0) AS refunds
                    FROM ledger_entry l
                    JOIN category c ON c.household_id = l.household_id AND c.id = l.category_id
                    WHERE l.household_id = :household AND l.status = 'POSTED'
                        AND l.occurred_on >= :start AND l.occurred_on < :end
                    GROUP BY c.id, c.name, c.kind
                    ORDER BY c.id
                    """.trimIndent(),
                ).param("household", owner.householdId)
                .param("start", range.startInclusive)
                .param("end", range.endExclusive)
                .query { row, _ ->
                    AggregateRow(
                        row.getObject("id", UUID::class.java),
                        row.getString("name"),
                        row.getString("kind"),
                        row.getBigDecimal("income"),
                        row.getBigDecimal("expenses"),
                        row.getBigDecimal("refunds"),
                    )
                }.list()
        val expenseRows = rows.filter { it.kind == "EXPENSE" }
        return PeriodAnalytics(
            Money.of(rows.sumOf(AggregateRow::income)),
            Money.of(expenseRows.sumOf(AggregateRow::expenses)),
            Money.of(expenseRows.sumOf(AggregateRow::refunds)),
            expenseRows.map {
                CategoryAnalytics(it.id, it.name, Money.of(it.expenses), Money.of(it.refunds))
            },
        )
    }

    private data class AggregateRow(
        val id: UUID,
        val name: String,
        val kind: String,
        val income: BigDecimal,
        val expenses: BigDecimal,
        val refunds: BigDecimal,
    )
}
