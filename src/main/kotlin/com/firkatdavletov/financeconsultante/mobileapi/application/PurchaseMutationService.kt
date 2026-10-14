package com.firkatdavletov.financeconsultante.mobileapi.application

import com.firkatdavletov.financeconsultante.access.application.FamilyBudgetAccess
import com.firkatdavletov.financeconsultante.access.domain.MemberRole
import com.firkatdavletov.financeconsultante.ledger.application.CorrectionResult
import com.firkatdavletov.financeconsultante.ledger.application.EntryCorrections
import com.firkatdavletov.financeconsultante.ledger.application.EntryTarget
import com.firkatdavletov.financeconsultante.ledger.application.HistoryItem
import com.firkatdavletov.financeconsultante.ledger.application.LedgerInteractionRepository
import com.firkatdavletov.financeconsultante.ledger.domain.EntryChange
import com.firkatdavletov.financeconsultante.ledger.domain.EntryStatus
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class InvalidPurchaseMutationRequest(
    message: String,
) : IllegalArgumentException(message)

class MobileApiPurchaseNotFound : NoSuchElementException("Purchase is unavailable")

class MobileApiPurchaseConflict(
    message: String,
) : IllegalStateException(message)

@Service
class PurchaseMutationService(
    private val access: FamilyBudgetAccess,
    private val ledger: LedgerInteractionRepository,
    private val corrections: EntryCorrections,
    private val clock: Clock,
) {
    @Transactional
    fun updateAmount(
        telegramUserId: Long,
        purchaseId: UUID,
        version: Long?,
        amount: String?,
    ): HistoryItem {
        val current = currentPurchase(telegramUserId, purchaseId, version)
        val result =
            corrections.change(
                current.target,
                EntryChange.Amount(parseAmount(amount, current.item)),
                source(purchaseId, "amount", current.target.version),
            )
        val updated = (result as? CorrectionResult.Updated)?.entry ?: conflict()
        return current.item.copy(entry = updated)
    }

    @Transactional(noRollbackFor = [MobileApiPurchaseConflict::class])
    fun refund(
        telegramUserId: Long,
        purchaseId: UUID,
        version: Long?,
    ) {
        val current = currentPurchase(telegramUserId, purchaseId, version)
        val result =
            corrections.refundRemaining(
                current.target,
                LocalDate.now(clock.withZone(API_ZONE)),
                source(purchaseId, "refund", current.target.version),
            )
        if (result !is CorrectionResult.Updated) conflict()
    }

    private fun currentPurchase(
        telegramUserId: Long,
        purchaseId: UUID,
        version: Long?,
    ): CurrentPurchase {
        val expectedVersion = parseVersion(version)
        val member = access.activeMember(telegramUserId) ?: throw MobileApiAccessDenied()
        val item = ledger.purchase(member.householdId, purchaseId) ?: notFound()
        if (member.role != MemberRole.OWNER && item.entry.actorId != member.id) notFound()
        if (item.entry.status != EntryStatus.POSTED || item.entry.version != expectedVersion) conflict()
        return CurrentPurchase(
            item,
            EntryTarget(member.householdId, member.id, purchaseId, expectedVersion, member.role),
        )
    }

    private fun parseAmount(
        value: String?,
        current: HistoryItem,
    ): Money {
        val amount = value ?: invalid("amount is required")
        if (!MONEY_PATTERN.matches(amount)) {
            invalid("amount must be a positive decimal with at most two digits")
        }
        return runCatching { Money.of(amount, current.entry.originalAmount.currency).requirePositive() }
            .getOrElse { invalid("amount must be positive and within the supported range") }
    }

    private fun parseVersion(value: Long?): Long {
        val version = value ?: invalid("version is required")
        if (version < 0) invalid("version must be non-negative")
        return version
    }

    private fun invalid(message: String): Nothing = throw InvalidPurchaseMutationRequest(message)

    private fun notFound(): Nothing = throw MobileApiPurchaseNotFound()

    private fun conflict(): Nothing = throw MobileApiPurchaseConflict("Purchase changed or cannot be modified")

    private fun source(
        purchaseId: UUID,
        action: String,
        version: Long,
    ): String = "mobile-api:purchase:$purchaseId:$action:$version"

    private data class CurrentPurchase(
        val item: HistoryItem,
        val target: EntryTarget,
    )

    companion object {
        private val API_ZONE = ZoneId.of("Asia/Almaty")
        private val MONEY_PATTERN = Regex("[0-9]+(?:\\.[0-9]{1,2})?")
    }
}
