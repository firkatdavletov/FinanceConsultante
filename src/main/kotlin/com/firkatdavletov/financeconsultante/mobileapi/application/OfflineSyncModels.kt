package com.firkatdavletov.financeconsultante.mobileapi.application

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class SyncScope(
    val householdId: UUID,
    val memberId: UUID,
    val role: String,
    val accountingStartedAt: Instant,
    val timeZone: String = "Asia/Almaty",
    val currency: String = "KZT",
)

data class SyncLedger(
    val id: UUID,
    val categoryId: UUID,
    val type: String,
    val originalAmount: String,
    val originalCurrency: String,
    val amountKzt: String,
    val occurredOn: LocalDate,
    val createdAt: Instant,
    val status: String,
    val version: Long,
    val sequence: Long,
    val description: String?,
    val refundOf: UUID?,
    val requestId: UUID?,
)

data class SyncCategory(
    val id: UUID,
    val name: String,
    val kind: String,
    val iconCode: String,
    val iconEmoji: String,
    val archived: Boolean,
)

data class SyncChange(
    val revision: Long,
    val kind: String,
    val deleted: Boolean,
    val operation: SyncLedger? = null,
    val category: SyncCategory? = null,
)

data class SyncPage(
    val scope: SyncScope,
    val changes: List<SyncChange>,
    val nextCursor: String,
    val hasMore: Boolean,
)

data class RecordedMobileOperation(
    val requestId: UUID,
    val operation: SyncLedger,
)

data class MobileOperationReceipt(
    val payloadHash: String,
    val entryId: UUID,
)

interface OfflineSyncRepository {
    fun revision(householdId: UUID): Long

    fun changes(
        householdId: UUID,
        memberId: UUID,
        after: Long,
        through: Long,
        limit: Int,
    ): List<SyncChange>

    fun receipt(
        householdId: UUID,
        memberId: UUID,
        requestId: UUID,
    ): MobileOperationReceipt?

    fun remember(
        householdId: UUID,
        memberId: UUID,
        requestId: UUID,
        payloadHash: String,
        entryId: UUID,
    )

    fun operation(
        householdId: UUID,
        memberId: UUID,
        entryId: UUID,
    ): SyncLedger
}

class InvalidOfflineRequest(
    message: String,
    val reason: String = "invalid_request",
) : IllegalArgumentException(message)

class OfflineSyncConflict(
    val code: String,
) : IllegalStateException(code)
