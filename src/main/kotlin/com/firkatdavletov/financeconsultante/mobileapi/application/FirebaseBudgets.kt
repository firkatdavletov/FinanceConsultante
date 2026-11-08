package com.firkatdavletov.financeconsultante.mobileapi.application

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class FirebaseBudgetSummary(
    val id: UUID,
    val name: String,
    val memberId: UUID,
    val role: String,
    val currency: String = "KZT",
    val currencyVersion: Long = 0,
)

interface FirebaseBudgetRepository {
    fun bindLegacy(
        uid: String,
        memberId: UUID,
    ): Boolean

    fun list(uid: String): List<FirebaseBudgetSummary>

    fun actor(
        uid: String,
        budgetId: UUID,
    ): CloudBudgetActor?
}

data class LegacyFirebaseBinding(
    val uid: String = "",
    val memberId: UUID? = null,
) {
    init {
        require(uid.isBlank() == (memberId == null)) { "Legacy Firebase binding requires both UID and member ID" }
        require(uid.length <= MAX_UID_LENGTH && uid.none(Char::isWhitespace)) { "Invalid legacy Firebase UID" }
    }

    companion object {
        private const val MAX_UID_LENGTH = 128
    }
}

class FirebaseBudgetBindingUnavailable : RuntimeException("Legacy budget binding is unavailable")

/** The identity is verified before entering these database-only transactions. */
@Service
class FirebaseBudgets(
    private val repository: FirebaseBudgetRepository,
    private val cloud: CloudBudgetService,
    private val legacy: LegacyFirebaseBinding,
) {
    @Transactional
    fun list(uid: String): List<FirebaseBudgetSummary> {
        bind(uid)
        return repository.list(uid)
    }

    @Transactional
    fun budget(
        uid: String,
        budgetId: UUID,
    ): CloudBudget = CloudBudget(actor(uid, budgetId).scope(), BUDGET_SYNC_CAPABILITIES + "categoryUsageV1")

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    fun changes(
        uid: String,
        budgetId: UUID,
        cursor: String?,
        limit: Int,
        capabilities: String? = null,
    ): SyncPage = cloud.changes(actor(uid, budgetId), cursor, limit, capabilities)

    @Transactional
    fun command(
        uid: String,
        budgetId: UUID,
        command: CloudBudgetCommand,
        capabilities: String? = null,
    ): BudgetCommandReceipt = cloud.command(actor(uid, budgetId), command, capabilities)

    internal fun actor(
        uid: String,
        budgetId: UUID,
    ): CloudBudgetActor {
        bind(uid)
        return repository.actor(uid, budgetId) ?: throw MobileApiAccessDenied()
    }

    private fun bind(uid: String) {
        val memberId = legacy.memberId ?: return
        if (uid == legacy.uid && !repository.bindLegacy(uid, memberId)) throw FirebaseBudgetBindingUnavailable()
    }
}
