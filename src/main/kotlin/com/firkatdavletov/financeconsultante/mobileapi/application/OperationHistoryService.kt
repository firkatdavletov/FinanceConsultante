package com.firkatdavletov.financeconsultante.mobileapi.application

import com.firkatdavletov.financeconsultante.access.application.FamilyBudgetAccess
import com.firkatdavletov.financeconsultante.ledger.application.HistoryItem
import com.firkatdavletov.financeconsultante.ledger.application.LedgerInteractionRepository
import com.firkatdavletov.financeconsultante.ledger.application.OperationHistoryFilter
import com.firkatdavletov.financeconsultante.ledger.application.OperationHistoryTotals
import com.firkatdavletov.financeconsultante.ledger.application.OperationHistoryTotalsRepository
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

data class OperationHistoryPage(
    val items: List<HistoryItem>,
    val nextCursor: Long?,
    val totals: OperationHistoryTotals,
)

class InvalidOperationHistoryRequest(
    message: String,
) : IllegalArgumentException(message)

class MobileApiAccessDenied : IllegalStateException("Active budget membership is required")

@Service
class OperationHistoryService(
    private val access: FamilyBudgetAccess,
    private val ledger: LedgerInteractionRepository,
    private val totalsRepository: OperationHistoryTotalsRepository,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun history(
        telegramUserId: Long,
        cursor: Long?,
        limit: Int,
        filter: OperationHistoryFilter = OperationHistoryFilter(),
    ): OperationHistoryPage {
        validatePagination(cursor, limit)
        validateFilter(filter)
        val member = access.activeMember(telegramUserId) ?: throw MobileApiAccessDenied()
        val rows = ledger.operationHistory(member.householdId, cursor ?: Long.MAX_VALUE, limit + 1, filter)
        val page = rows.take(limit)
        return OperationHistoryPage(
            items = page,
            nextCursor = page.lastOrNull()?.sequence?.takeIf { rows.size > page.size },
            totals = totalsRepository.totals(member.householdId, filter.dateFrom, filter.dateTo),
        )
    }

    private fun validateFilter(filter: OperationHistoryFilter) {
        if (filter.type == Direction.EXPENSE_REFUND) {
            throw InvalidOperationHistoryRequest("type must be EXPENSE or INCOME")
        }
        if (filter.dateFrom != null && filter.dateTo != null && filter.dateFrom > filter.dateTo) {
            throw InvalidOperationHistoryRequest("dateFrom must not be after dateTo")
        }
    }

    private fun validatePagination(
        cursor: Long?,
        limit: Int,
    ) {
        if (cursor != null && cursor <= 0) {
            throw InvalidOperationHistoryRequest("cursor must be positive")
        }
        if (limit !in 1..MAX_PAGE_SIZE) {
            throw InvalidOperationHistoryRequest("limit must be between 1 and $MAX_PAGE_SIZE")
        }
    }

    companion object {
        const val MAX_PAGE_SIZE = 50
    }
}
