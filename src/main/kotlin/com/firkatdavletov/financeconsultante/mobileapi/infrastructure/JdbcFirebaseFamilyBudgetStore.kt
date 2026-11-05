package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseBudgetMember
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseBudgetSummary
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseFamilyBudgetStore
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseInvitationAcceptance
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseMembership
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JdbcFirebaseFamilyBudgetStore(
    private val jdbc: JdbcClient,
) : FirebaseFamilyBudgetStore {
    override fun invitation(
        hash: String,
        lock: Boolean,
    ): FirebaseInvitationAcceptance? =
        jdbc
            .sql(
                "SELECT * FROM firebase_budget_invitation WHERE code_hash = ?" + if (lock) " FOR UPDATE" else "",
            ).param(hash)
            .query { row, _ ->
                FirebaseInvitationAcceptance(
                    row.getObject("id", UUID::class.java),
                    row.getObject("household_id", UUID::class.java),
                    row.getObject("expires_at", OffsetDateTime::class.java).toInstant(),
                    row.getObject("revoked_at") != null,
                    row.getObject("accepted_at") != null,
                    row.getString("accepted_by_uid"),
                    row.getObject("accepted_member_id", UUID::class.java),
                )
            }.optional()
            .orElse(null)

    override fun lock(budgetId: UUID) {
        jdbc
            .sql("SELECT id FROM household WHERE id = ? FOR UPDATE")
            .param(budgetId)
            .query(UUID::class.java)
            .single()
    }

    override fun membership(
        uid: String,
        budgetId: UUID,
    ): FirebaseMembership? =
        jdbc
            .sql(
                """
                SELECT m.id, m.role, m.revoked_at FROM firebase_budget_access b
                JOIN member m ON m.id = b.member_id AND m.household_id = b.household_id
                WHERE b.firebase_uid = ? AND b.household_id = ?
                """.trimIndent(),
            ).params(uid, budgetId)
            .query { row, _ ->
                FirebaseMembership(
                    row.getObject("id", UUID::class.java),
                    row.getString("role"),
                    row.getObject("revoked_at") == null,
                )
            }.optional()
            .orElse(null)

    override fun addMember(
        uid: String,
        budgetId: UUID,
        now: Instant,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc
            .sql("INSERT INTO member(id, household_id, role, joined_at) VALUES (?, ?, 'MEMBER', ?)")
            .params(id, budgetId, now.atOffset(ZoneOffset.UTC))
            .update()
        jdbc
            .sql(
                """
                INSERT INTO firebase_budget_access(firebase_uid, household_id, member_id) VALUES (?, ?, ?)
                ON CONFLICT (firebase_uid, household_id) DO UPDATE SET member_id = EXCLUDED.member_id
                """.trimIndent(),
            ).params(uid, budgetId, id)
            .update()
        return id
    }

    override fun accept(
        invitationId: UUID,
        uid: String,
        memberId: UUID,
        now: Instant,
    ) {
        jdbc
            .sql(
                "UPDATE firebase_budget_invitation SET accepted_at = ?, accepted_by_uid = ?, " +
                    "accepted_member_id = ? WHERE id = ?",
            ).params(now.atOffset(ZoneOffset.UTC), uid, memberId, invitationId)
            .update()
    }

    override fun summary(
        budgetId: UUID,
        memberId: UUID,
    ): FirebaseBudgetSummary =
        jdbc
            .sql(
                """
                SELECT h.budget_name, h.budget_currency, h.budget_currency_version, m.role
                FROM household h JOIN member m ON m.household_id = h.id WHERE h.id = ? AND m.id = ?
                """.trimIndent(),
            ).params(budgetId, memberId)
            .query {
                row,
                _,
                ->
                FirebaseBudgetSummary(
                    budgetId,
                    row.getString("budget_name"),
                    memberId,
                    row.getString("role"),
                    row.getString("budget_currency"),
                    row.getLong("budget_currency_version"),
                )
            }.single()

    override fun members(
        uid: String,
        budgetId: UUID,
    ): List<FirebaseBudgetMember> =
        jdbc
            .sql(
                """
                SELECT m.id, m.role, m.joined_at, u.display_name, COALESCE(b.firebase_uid = ?, FALSE) AS is_current
                FROM member m
                LEFT JOIN firebase_budget_access b ON b.member_id = m.id AND b.household_id = m.household_id
                LEFT JOIN firebase_user u ON u.firebase_uid = b.firebase_uid
                WHERE m.household_id = ? AND m.revoked_at IS NULL ORDER BY m.role DESC, m.joined_at, m.id
                """.trimIndent(),
            ).params(uid, budgetId)
            .query { row, _ ->
                FirebaseBudgetMember(
                    row.getObject("id", UUID::class.java),
                    row.getString("display_name")?.takeIf { it.isNotBlank() }
                        ?: if (row.getString("role") == "OWNER") "Владелец" else "Участник",
                    row.getString("role"),
                    row.getObject("joined_at", OffsetDateTime::class.java).toInstant(),
                    row.getBoolean("is_current"),
                )
            }.list()

    override fun memberRole(
        budgetId: UUID,
        memberId: UUID,
    ): String? =
        jdbc
            .sql(
                "SELECT role FROM member WHERE household_id = ? AND id = ?",
            ).params(budgetId, memberId)
            .query(String::class.java)
            .optional()
            .orElse(null)

    override fun revoke(
        budgetId: UUID,
        memberId: UUID,
        now: Instant,
    ): Boolean =
        jdbc
            .sql(
                """
                UPDATE member SET revoked_at = ?, reports_enabled = FALSE
                WHERE household_id = ? AND id = ? AND role = 'MEMBER' AND revoked_at IS NULL
                """.trimIndent(),
            ).params(now.atOffset(ZoneOffset.UTC), budgetId, memberId)
            .update() == 1
}
