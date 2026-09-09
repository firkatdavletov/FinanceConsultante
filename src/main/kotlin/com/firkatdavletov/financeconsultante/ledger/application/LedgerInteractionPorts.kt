package com.firkatdavletov.financeconsultante.ledger.application

import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.ledger.domain.LedgerEntry
import com.firkatdavletov.financeconsultante.ledger.domain.TransactionCategory
import com.firkatdavletov.financeconsultante.ledger.domain.TransactionDraft
import java.time.Instant
import java.util.UUID

data class OwnedLedger(
    val householdId: UUID,
    val memberId: UUID,
    val ownerId: Long,
    val startedAt: Instant,
)

data class HistoryItem(
    val sequence: Long,
    val category: String,
    val entry: LedgerEntry,
)

interface LedgerInteractionRepository {
    /** Locks the account to serialize financial writes and opening changes. */
    fun owned(ownerId: Long): OwnedLedger?

    fun resolveCategory(
        householdId: UUID,
        key: TransactionCategory,
    ): Category

    fun history(
        householdId: UUID,
        before: Long,
        limit: Int,
    ): List<HistoryItem>
}

interface TransactionDraftRepository {
    fun active(
        owner: OwnedLedger,
        botId: Long,
    ): TransactionDraft?

    fun find(
        owner: OwnedLedger,
        botId: Long,
        id: UUID,
    ): TransactionDraft?

    /** Saves a new draft or checks the previous version before updating it. */
    fun save(
        owner: OwnedLedger,
        botId: Long,
        draft: TransactionDraft,
    )
}
