package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import com.firkatdavletov.financeconsultante.auth.infrastructure.JdbcFirebaseAccountAccess
import com.firkatdavletov.financeconsultante.auth.infrastructure.deletionHash
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetFailure
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseBudgetRepository
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseBudgetSummary
import com.firkatdavletov.financeconsultante.mobileapi.application.MobileApiAccessDenied
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class RenameFirebaseBudget(
    val requestId: UUID,
    val name: String,
)

private const val MAX_NAME_LENGTH = 80

@Service
class FirebaseBudgetManagement(
    private val jdbc: JdbcClient,
    private val access: JdbcFirebaseAccountAccess,
    private val budgets: FirebaseBudgetRepository,
) {
    @Transactional
    fun rename(
        uid: String,
        id: UUID,
        request: RenameFirebaseBudget,
    ): FirebaseBudgetSummary {
        require(
            request.name == request.name.trim() && request.name.length in 1..MAX_NAME_LENGTH &&
                request.name.none(
                    Char::isISOControl,
                ),
        )
        lock(uid, id, request.requestId)
        val payload = deletionHash("RENAME:$id:${request.name}")
        val previous = receipt(uid, request.requestId, payload)
        val actor = budgets.actor(uid, id) ?: throw MobileApiAccessDenied()
        if (actor.role.name != "OWNER") throw MobileApiAccessDenied()
        if (!previous) {
            jdbc.sql("UPDATE household SET budget_name=? WHERE id=?").params(request.name, id).update()
            remember(uid, request.requestId, payload)
        }
        return budgets.list(uid).single { it.id == id }
    }

    @Transactional
    fun delete(
        uid: String,
        id: UUID,
        requestId: UUID,
    ) {
        lock(uid, id, requestId)
        val payload = deletionHash("DELETE:$id")
        if (receipt(uid, requestId, payload)) return
        val actor = budgets.actor(uid, id) ?: throw MobileApiAccessDenied()
        if (actor.role.name != "OWNER") throw MobileApiAccessDenied()
        jdbc.sql("INSERT INTO firebase_deleted_budget(budget_id) VALUES (?) ON CONFLICT DO NOTHING").param(id).update()
        jdbc
            .sql(
                """
                INSERT INTO firebase_deleted_creation(uid_hash, request_id)
                SELECT ?, request_id FROM firebase_budget_creation WHERE household_id=?
                ON CONFLICT DO NOTHING
                """.trimIndent(),
            ).params(deletionHash(uid), id)
            .update()
        jdbc
            .sql("SELECT erase_firebase_budget(?)")
            .param(id)
            .query { _, _ -> Unit }
            .single()
        remember(uid, requestId, payload)
    }

    private fun lock(
        uid: String,
        id: UUID,
        requestId: UUID,
    ) {
        access.active(uid)
        access.lock("profile-budget:$id", false)
        access.lock("budget-management:$uid:$requestId", false)
    }

    private fun receipt(
        uid: String,
        requestId: UUID,
        payload: String,
    ): Boolean {
        val previous =
            jdbc
                .sql("SELECT payload_hash FROM firebase_budget_management WHERE uid_hash=? AND request_id=?")
                .params(deletionHash(uid), requestId)
                .query(String::class.java)
                .optional()
                .orElse(null) ?: return false
        if (previous != payload) throw CloudBudgetFailure("idempotency_conflict")
        return true
    }

    private fun remember(
        uid: String,
        requestId: UUID,
        payload: String,
    ) {
        jdbc
            .sql("INSERT INTO firebase_budget_management(uid_hash, request_id, payload_hash) VALUES (?, ?, ?)")
            .params(deletionHash(uid), requestId, payload)
            .update()
    }
}
