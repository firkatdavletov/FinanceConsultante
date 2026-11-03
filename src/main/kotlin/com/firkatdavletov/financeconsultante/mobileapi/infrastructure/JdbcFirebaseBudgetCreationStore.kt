package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import com.firkatdavletov.financeconsultante.mobileapi.application.CreateCloudBudget
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseBudgetCreationStore
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseBudgetSummary
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseCreationReceipt
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class JdbcFirebaseBudgetCreationStore(
    private val jdbc: JdbcClient,
    private val bootstrap: JdbcCloudBudgetBootstrap,
) : FirebaseBudgetCreationStore {
    override fun lockImportIdentities(request: CreateCloudBudget) {
        val ids = listOf(request.householdId, request.memberId) + request.categories.map { it.id }
        ids.distinct().sorted().forEach { id ->
            jdbc
                .sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
                .param("firebase-import:$id")
                .query { _, _ -> true }
                .single()
        }
    }

    override fun identitiesAvailable(request: CreateCloudBudget): Boolean =
        jdbc
            .sql(
                """
                SELECT NOT (EXISTS(SELECT 1 FROM firebase_deleted_budget WHERE budget_id = ?) OR
                EXISTS(SELECT 1 FROM household WHERE id = ?) OR
                EXISTS(SELECT 1 FROM member WHERE id = ?) OR
                EXISTS(SELECT 1 FROM category WHERE id IN (?, ?, ?, ?)))
                """.trimIndent(),
            ).params(
                request.householdId,
                request.householdId,
                request.memberId,
                request.categories[0].id,
                request.categories[1].id,
                request.categories[2].id,
                request.categories.last().id,
            ).query(Boolean::class.javaObjectType)
            .single()

    override fun lock(
        uid: String,
        requestId: UUID,
    ) {
        jdbc
            .sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
            .param("firebase-create:${uid.length}:$uid:$requestId")
            .query { _, _ -> true }
            .single()
        if (jdbc
                .sql("SELECT EXISTS(SELECT 1 FROM firebase_deleted_creation WHERE uid_hash=? AND request_id=?)")
                .params(
                    com.firkatdavletov.financeconsultante.auth.infrastructure
                        .deletionHash(uid),
                    requestId,
                ).query(Boolean::class.java)
                .single()
        ) {
            throw com.firkatdavletov.financeconsultante.mobileapi.application
                .CloudBudgetFailure("budget_deleted")
        }
    }

    override fun receipt(
        uid: String,
        requestId: UUID,
    ): FirebaseCreationReceipt? =
        jdbc
            .sql(
                """
                SELECT c.payload_hash, h.id, h.budget_name, b.member_id FROM firebase_budget_creation c
                JOIN household h ON h.id = c.household_id
                JOIN firebase_budget_access b ON b.firebase_uid = c.firebase_uid AND b.household_id = c.household_id
                WHERE c.firebase_uid = ? AND c.request_id = ?
                """.trimIndent(),
            ).params(uid, requestId)
            .query { row, _ ->
                FirebaseCreationReceipt(
                    row.getString("payload_hash"),
                    FirebaseBudgetSummary(
                        row.getObject("id", UUID::class.java),
                        row.getString("budget_name"),
                        row.getObject("member_id", UUID::class.java),
                        "OWNER",
                    ),
                )
            }.optional()
            .orElse(null)

    override fun create(
        uid: String,
        name: String,
        request: CreateCloudBudget,
        payloadHash: String,
    ): FirebaseBudgetSummary {
        bootstrap.create(request, name)
        jdbc
            .sql("INSERT INTO firebase_budget_access(firebase_uid, household_id, member_id) VALUES (?, ?, ?)")
            .params(uid, request.householdId, request.memberId)
            .update()
        jdbc
            .sql(
                """
                INSERT INTO firebase_budget_creation(firebase_uid, request_id, payload_hash, household_id)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
            ).params(uid, request.requestId, payloadHash, request.householdId)
            .update()
        return FirebaseBudgetSummary(request.householdId, name, request.memberId, "OWNER")
    }
}
