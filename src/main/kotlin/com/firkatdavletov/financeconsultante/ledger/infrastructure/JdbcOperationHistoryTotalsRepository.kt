package com.firkatdavletov.financeconsultante.ledger.infrastructure

import com.firkatdavletov.financeconsultante.ledger.application.OperationHistoryTotals
import com.firkatdavletov.financeconsultante.ledger.application.OperationHistoryTotalsRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

@Repository
@Transactional(readOnly = true, propagation = Propagation.MANDATORY)
class JdbcOperationHistoryTotalsRepository(
    private val jdbc: JdbcClient,
) : OperationHistoryTotalsRepository {
    override fun totals(
        householdId: UUID,
        dateFrom: LocalDate?,
        dateTo: LocalDate?,
    ): OperationHistoryTotals {
        val conditions = mutableListOf("household_id = ?", "status = 'POSTED'")
        val parameters = mutableListOf<Any>(householdId)
        dateFrom?.let {
            conditions += "occurred_on >= ?"
            parameters += it
        }
        dateTo?.let {
            conditions += "occurred_on <= ?"
            parameters += it
        }
        return jdbc
            .sql(
                """
                SELECT COALESCE(SUM(amount_kzt) FILTER (WHERE direction = 'EXPENSE'), 0) AS expenses,
                    COALESCE(SUM(amount_kzt) FILTER (WHERE direction = 'INCOME'), 0) AS incomes,
                    COALESCE(SUM(amount_kzt) FILTER (WHERE direction = 'EXPENSE_REFUND'), 0) AS refunds
                FROM ledger_entry WHERE ${conditions.joinToString(" AND ")}
                """.trimIndent(),
            ).params(parameters)
            .query { row, _ ->
                OperationHistoryTotals(
                    expenses = row.getBigDecimal("expenses") - row.getBigDecimal("refunds"),
                    incomes = row.getBigDecimal("incomes"),
                )
            }.single()
    }
}
