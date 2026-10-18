package com.firkatdavletov.financeconsultante.mobileapi.application

import com.firkatdavletov.financeconsultante.access.application.FamilyBudgetAccess
import com.firkatdavletov.financeconsultante.ledger.application.AccountingService
import com.firkatdavletov.financeconsultante.ledger.application.BudgetRepository
import com.firkatdavletov.financeconsultante.ledger.application.RecordEntry
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.ledger.domain.ExpenseDescriptionNormalizer
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class CreateOfflineOperationRequest(
    val requestId: UUID,
    val householdId: UUID,
    val memberId: UUID,
    val type: String,
    val categoryId: UUID,
    val amount: String,
    val currency: String,
    val occurredOn: LocalDate,
    val description: String? = null,
)

@Service
class CreateOfflineOperation(
    private val access: FamilyBudgetAccess,
    private val budgets: BudgetRepository,
    private val accounting: AccountingService,
    private val repository: OfflineSyncRepository,
    private val clock: Clock,
) {
    @Transactional
    fun create(
        telegramUserId: Long,
        request: CreateOfflineOperationRequest,
    ): RecordedMobileOperation {
        val member = access.activeMember(telegramUserId) ?: throw MobileApiAccessDenied()
        if (request.householdId != member.householdId || request.memberId != member.id) {
            conflict("scope_changed")
        }
        val account =
            budgets.findOwnedAccount(member.householdId, member.id, lock = true) ?: throw MobileApiAccessDenied()
        val hash = hash(request)
        repository.receipt(member.householdId, member.id, request.requestId)?.let { receipt ->
            if (receipt.payloadHash != hash) conflict("idempotency_conflict")
            return RecordedMobileOperation(
                request.requestId,
                repository.operation(member.householdId, member.id, receipt.entryId),
            )
        }
        val direction =
            Direction.entries.firstOrNull { it.name == request.type && it != Direction.EXPENSE_REFUND }
                ?: invalid("type must be EXPENSE or INCOME")
        val amount = parseAmount(request)
        val occurredAt = request.occurredOn.atStartOfDay(ZONE).toInstant()
        if (occurredAt < account.accountingStartedAt || occurredAt > clock.instant()) {
            invalid("Date is outside accounting interval", "invalid_date")
        }
        val category = budgets.findCategory(member.householdId, request.categoryId)
        if (category == null || category.archived || category.kind != direction.categoryKind) {
            invalid("Category is unavailable", "category_unavailable")
        }
        val description = description(request.description)
        val entry =
            accounting
                .record(
                    RecordEntry(
                        member.householdId,
                        member.id,
                        request.categoryId,
                        direction,
                        amount,
                        occurredAt,
                        "mobile-api:create:${member.id}:${request.requestId}",
                        description,
                    ),
                ).entry
        repository.remember(member.householdId, member.id, request.requestId, hash, entry.id)
        return RecordedMobileOperation(request.requestId, repository.operation(member.householdId, member.id, entry.id))
    }

    private fun parseAmount(request: CreateOfflineOperationRequest): Money {
        if (request.currency != "KZT" || !AMOUNT.matches(request.amount)) {
            invalid("A positive KZT amount with at most two decimals is required", "invalid_amount")
        }
        return runCatching { Money.of(request.amount).requirePositive() }
            .getOrElse { invalid("Amount is outside supported range", "invalid_amount") }
    }

    private fun description(value: String?): String? {
        if (value.isNullOrBlank()) return null
        if (value.length >
            ExpenseDescriptionNormalizer.MAX_LENGTH
        ) {
            invalid("Description is too long", "invalid_description")
        }
        return ExpenseDescriptionNormalizer.normalize(value) ?: invalid("Invalid description", "invalid_description")
    }

    private fun hash(request: CreateOfflineOperationRequest): String {
        // Length prefixes avoid ambiguous concatenation; retain the original payload even after ledger corrections.
        val fields =
            listOf(
                request.householdId,
                request.memberId,
                request.type,
                request.categoryId,
                request.amount,
                request.currency,
                request.occurredOn,
                request.description.orEmpty(),
            )
        val payload = fields.joinToString("") { "${it.toString().length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun invalid(
        message: String,
        reason: String = "invalid_request",
    ): Nothing = throw InvalidOfflineRequest(message, reason)

    private fun conflict(code: String): Nothing = throw OfflineSyncConflict(code)

    companion object {
        private val ZONE = ZoneId.of("Asia/Almaty")
        private val AMOUNT = Regex("[0-9]+(?:\\.[0-9]{1,2})?")
    }
}
