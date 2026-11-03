package com.firkatdavletov.financeconsultante.mobileapi.application

import com.firkatdavletov.financeconsultante.auth.application.FirebaseAccountAccess
import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.ledger.domain.TransactionCategory
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class CreateFirebaseBudget(
    val requestId: UUID,
    val name: String,
    val openingBalance: String,
    val accountingStartedOn: LocalDate,
    val currency: String = "KZT",
)

data class ImportFirebaseBudget(
    val name: String,
    val budget: CreateCloudBudget,
)

data class FirebaseCreationReceipt(
    val payloadHash: String,
    val budget: FirebaseBudgetSummary,
)

interface FirebaseBudgetCreationStore {
    fun lockImportIdentities(request: CreateCloudBudget)

    fun identitiesAvailable(request: CreateCloudBudget): Boolean

    fun lock(
        uid: String,
        requestId: UUID,
    )

    fun receipt(
        uid: String,
        requestId: UUID,
    ): FirebaseCreationReceipt?

    fun create(
        uid: String,
        name: String,
        request: CreateCloudBudget,
        payloadHash: String,
    ): FirebaseBudgetSummary
}

/** Firebase verification and user upsert finish before this database-only transaction starts. */
@Service
class FirebaseBudgetCreation(
    private val store: FirebaseBudgetCreationStore,
    private val budgets: FirebaseBudgetRepository,
    private val clock: Clock,
    private val bootstrapValidator: CloudBudgetBootstrapValidator,
    private val accountAccess: FirebaseAccountAccess,
) {
    @Transactional
    fun create(
        uid: String,
        request: CreateFirebaseBudget,
    ): FirebaseBudgetSummary {
        accountAccess.active(uid)
        val fingerprint = fingerprint(request)
        store.lock(uid, request.requestId)
        previous(uid, request.requestId, fingerprint)?.let { return it }
        validateName(request.name)
        require(request.currency == "KZT" && request.openingBalance.length <= MAX_BALANCE_LENGTH)
        val balance = Money.of(request.openingBalance).amount.toPlainString()
        val today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Almaty")))
        require(request.accountingStartedOn.year >= 1 && request.accountingStartedOn <= today)
        val bootstrap =
            CreateCloudBudget(
                request.requestId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                balance,
                request.accountingStartedOn,
                TransactionCategory.entries.map { category ->
                    CloudSeedCategory(
                        UUID.randomUUID(),
                        category.displayName,
                        category.direction.name,
                        Category.defaultIconFor(category.name),
                        category.name,
                    )
                },
                "KZT",
            )
        return store.create(uid, request.name, bootstrap, fingerprint)
    }

    @Transactional
    fun importLocal(
        uid: String,
        request: ImportFirebaseBudget,
    ): FirebaseBudgetSummary {
        accountAccess.active(uid)
        val budget = request.budget
        val fingerprint =
            hash(
                listOf(
                    "local-budget-v1",
                    request.name,
                    budget.householdId,
                    budget.memberId,
                    budget.openingBalance,
                    budget.accountingStartedOn,
                    budget.currency,
                ) +
                    budget.categories.flatMap { listOf(it.id, it.name, it.kind, it.icon, it.dictionaryKey) },
            )
        store.lock(uid, budget.requestId)
        previous(uid, budget.requestId, fingerprint)?.let { return it }
        validateName(request.name)
        bootstrapValidator.validate(budget)
        store.lockImportIdentities(budget)
        if (!store.identitiesAvailable(budget)) throw CloudBudgetFailure("budget_identity_conflict")
        return store.create(uid, request.name, budget, fingerprint)
    }

    private fun previous(
        uid: String,
        requestId: UUID,
        fingerprint: String,
    ): FirebaseBudgetSummary? {
        val receipt = store.receipt(uid, requestId) ?: return null
        if (receipt.payloadHash != fingerprint) throw CloudBudgetFailure("idempotency_conflict")
        budgets.actor(uid, receipt.budget.id) ?: throw MobileApiAccessDenied()
        return receipt.budget
    }

    private fun validateName(name: String) {
        require(name == name.trim() && name.length in 1..MAX_NAME_LENGTH && name.none(Char::isISOControl))
    }

    private fun fingerprint(request: CreateFirebaseBudget): String =
        hash(listOf(request.name, request.openingBalance, request.accountingStartedOn, request.currency))

    private fun hash(values: List<Any?>): String {
        val payload = values.joinToString("") { "$it".let { value -> "${value.length}:$value" } }
        return MessageDigest
            .getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAX_NAME_LENGTH = 80
        private const val MAX_BALANCE_LENGTH = 64
    }
}
