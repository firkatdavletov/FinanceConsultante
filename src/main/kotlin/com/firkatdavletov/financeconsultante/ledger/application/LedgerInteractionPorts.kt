package com.firkatdavletov.financeconsultante.ledger.application

import com.firkatdavletov.financeconsultante.access.domain.MemberRole
import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.ledger.domain.LedgerEntry
import com.firkatdavletov.financeconsultante.ledger.domain.TransactionCategory
import com.firkatdavletov.financeconsultante.ledger.domain.TransactionDraft
import java.time.Instant
import java.util.UUID

interface BudgetActor {
    val householdId: UUID
    val memberId: UUID
    val startedAt: Instant
    val role: MemberRole
    val canManageHousehold: Boolean get() = role == MemberRole.OWNER
}

data class OwnedLedger(
    override val householdId: UUID,
    override val memberId: UUID,
    val ownerId: Long,
    override val startedAt: Instant,
    override val role: MemberRole = MemberRole.OWNER,
) : BudgetActor

data class HistoryItem(
    val sequence: Long,
    val category: String,
    val entry: LedgerEntry,
    val actorTelegramUserId: Long? = null,
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

    /** Returns expenses and income, applying all filters before pagination; refunds are excluded. */
    fun operationHistory(
        householdId: UUID,
        before: Long,
        limit: Int,
        filter: OperationHistoryFilter,
    ): List<HistoryItem>

    /** Returns one expense from the household history; income and refund entries are not purchases. */
    fun purchase(
        householdId: UUID,
        entryId: UUID,
    ): HistoryItem? = null
}

interface TransactionDraftRepository {
    fun active(
        owner: OwnedLedger,
        botId: Long,
    ): List<TransactionDraft>

    fun needsInput(
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
