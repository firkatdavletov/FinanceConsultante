package com.firkatdavletov.financeconsultante.ledger.application

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class OperationHistoryTotals(
    val expenses: BigDecimal,
    val incomes: BigDecimal,
)

interface OperationHistoryTotalsRepository {
    /** Posted KZT totals for the entire period; refunds reduce expenses on their own local date. */
    fun totals(
        householdId: UUID,
        dateFrom: LocalDate?,
        dateTo: LocalDate?,
    ): OperationHistoryTotals
}
