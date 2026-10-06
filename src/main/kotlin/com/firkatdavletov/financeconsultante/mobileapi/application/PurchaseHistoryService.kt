package com.firkatdavletov.financeconsultante.mobileapi.application

import com.firkatdavletov.financeconsultante.access.application.FamilyBudgetAccess
import com.firkatdavletov.financeconsultante.ledger.application.HistoryItem
import com.firkatdavletov.financeconsultante.ledger.application.LedgerInteractionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

data class PurchaseHistoryPage(
    val items: List<HistoryItem>,
    val nextCursor: Long?,
)

class InvalidPurchaseHistoryRequest(
    message: String,
) : IllegalArgumentException(message)

class MobileApiAccessDenied : IllegalStateException("Active budget membership is required")

@Service
class PurchaseHistoryService(
    private val access: FamilyBudgetAccess,
    private val ledger: LedgerInteractionRepository,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun history(
        telegramUserId: Long,
        cursor: Long?,
        limit: Int,
    ): PurchaseHistoryPage {
        validatePagination(cursor, limit)
        val member = access.activeMember(telegramUserId) ?: throw MobileApiAccessDenied()
        val rows = ledger.purchaseHistory(member.householdId, cursor ?: Long.MAX_VALUE, limit + 1)
        val page = rows.take(limit)
        return PurchaseHistoryPage(
            items = page,
            nextCursor = page.lastOrNull()?.sequence?.takeIf { rows.size > page.size },
        )
    }

    private fun validatePagination(
        cursor: Long?,
        limit: Int,
    ) {
        if (cursor != null && cursor <= 0) {
            throw InvalidPurchaseHistoryRequest("cursor must be positive")
        }
        if (limit !in 1..MAX_PAGE_SIZE) {
            throw InvalidPurchaseHistoryRequest("limit must be between 1 and $MAX_PAGE_SIZE")
        }
    }

    companion object {
        const val MAX_PAGE_SIZE = 50
    }
}
