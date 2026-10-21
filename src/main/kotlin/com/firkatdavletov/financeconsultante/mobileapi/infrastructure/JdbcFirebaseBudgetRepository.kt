package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import com.firkatdavletov.financeconsultante.access.domain.MemberRole
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetActor
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseBudgetRepository
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseBudgetSummary
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JdbcFirebaseBudgetRepository(
    private val jdbc: JdbcClient,
) : FirebaseBudgetRepository {
    override fun bindLegacy(
        uid: String,
        memberId: UUID,
    ): Boolean {
        val bound =
            jdbc
                .sql("SELECT COUNT(*) FROM firebase_budget_access WHERE firebase_uid = ? AND member_id = ?")
                .params(uid, memberId)
                .query(Int::class.javaObjectType)
                .single() == 1
        return bound || importLegacyMember(uid, memberId)
    }

    private fun importLegacyMember(
        uid: String,
        memberId: UUID,
    ): Boolean {
        // Serializes the explicitly configured member, including first requests from different UIDs.
        jdbc
            .sql("SELECT id FROM member WHERE id = ? AND revoked_at IS NULL FOR UPDATE")
            .param(memberId)
            .query(UUID::class.java)
            .optional()
            .orElse(null) ?: return false
        jdbc
            .sql(
                """
                INSERT INTO firebase_budget_access(firebase_uid, household_id, member_id)
                SELECT ?, m.household_id, m.id FROM member m JOIN account a ON a.household_id = m.household_id
                WHERE m.id = ? AND m.revoked_at IS NULL ON CONFLICT DO NOTHING
                """.trimIndent(),
            ).params(uid, memberId)
            .update()
        return jdbc
            .sql("SELECT COUNT(*) FROM firebase_budget_access WHERE firebase_uid = ? AND member_id = ?")
            .params(uid, memberId)
            .query(Int::class.javaObjectType)
            .single() == 1
    }

    override fun list(uid: String): List<FirebaseBudgetSummary> =
        jdbc
            .sql(
                """
                SELECT b.household_id, h.budget_name, m.id, m.role FROM firebase_budget_access b
                JOIN member m ON m.household_id = b.household_id AND m.id = b.member_id
                JOIN household h ON h.id = b.household_id JOIN account a ON a.household_id = h.id
                WHERE b.firebase_uid = ? AND m.revoked_at IS NULL ORDER BY h.budget_name, h.id
                """.trimIndent(),
            ).param(uid)
            .query { row, _ ->
                FirebaseBudgetSummary(
                    row.getObject("household_id", UUID::class.java),
                    row.getString("budget_name"),
                    row.getObject("id", UUID::class.java),
                    row.getString("role"),
                )
            }.list()

    override fun actor(
        uid: String,
        budgetId: UUID,
    ): CloudBudgetActor? =
        jdbc
            .sql(
                """
                SELECT m.household_id, m.id, m.role, a.accounting_started_at FROM firebase_budget_access b
                JOIN member m ON m.household_id = b.household_id AND m.id = b.member_id
                JOIN account a ON a.household_id = m.household_id
                WHERE b.firebase_uid = ? AND b.household_id = ? AND m.revoked_at IS NULL FOR SHARE OF b, m
                """.trimIndent(),
            ).params(uid, budgetId)
            .query { row, _ ->
                CloudBudgetActor(
                    row.getObject("household_id", UUID::class.java),
                    row.getObject("id", UUID::class.java),
                    row.getObject("accounting_started_at", OffsetDateTime::class.java).toInstant(),
                    MemberRole.valueOf(row.getString("role")),
                )
            }.optional()
            .orElse(null)
}
