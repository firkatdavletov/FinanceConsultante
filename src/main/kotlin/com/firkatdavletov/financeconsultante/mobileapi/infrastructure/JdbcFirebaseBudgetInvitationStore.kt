package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseBudgetInvitationStore
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseInvitationInfo
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JdbcFirebaseBudgetInvitationStore(
    private val jdbc: JdbcClient,
) : FirebaseBudgetInvitationStore {
    override fun lock(budgetId: UUID) {
        jdbc
            .sql("SELECT id FROM household WHERE id = ? FOR UPDATE")
            .param(budgetId)
            .query(UUID::class.java)
            .single()
    }

    override fun current(
        budgetId: UUID,
        now: Instant,
    ): FirebaseInvitationInfo? =
        jdbc
            .sql(
                """
                SELECT id, household_id, expires_at FROM firebase_budget_invitation
                WHERE household_id = ? AND revoked_at IS NULL AND accepted_at IS NULL AND expires_at > ?
                """.trimIndent(),
            ).params(budgetId, now.atOffset(ZoneOffset.UTC))
            .query { row, _ ->
                FirebaseInvitationInfo(
                    row.getObject("id", UUID::class.java),
                    row.getObject("household_id", UUID::class.java),
                    row.getObject("expires_at", OffsetDateTime::class.java).toInstant(),
                )
            }.optional()
            .orElse(null)

    override fun replace(
        invitation: FirebaseInvitationInfo,
        memberId: UUID,
        codeHash: String,
        now: Instant,
    ) {
        jdbc
            .sql(
                """
                UPDATE firebase_budget_invitation SET revoked_at = ?
                WHERE household_id = ? AND revoked_at IS NULL AND accepted_at IS NULL
                """.trimIndent(),
            ).params(now.atOffset(ZoneOffset.UTC), invitation.budgetId)
            .update()
        jdbc
            .sql(
                """
                INSERT INTO firebase_budget_invitation(id, household_id, invited_by, code_hash, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).params(
                invitation.id,
                invitation.budgetId,
                memberId,
                codeHash,
                now.atOffset(ZoneOffset.UTC),
                invitation.expiresAt.atOffset(ZoneOffset.UTC),
            ).update()
    }

    override fun revoke(
        budgetId: UUID,
        invitationId: UUID,
        now: Instant,
    ) {
        jdbc
            .sql(
                """
                UPDATE firebase_budget_invitation SET revoked_at = ?
                WHERE household_id = ? AND id = ? AND revoked_at IS NULL AND accepted_at IS NULL
                """.trimIndent(),
            ).params(now.atOffset(ZoneOffset.UTC), budgetId, invitationId)
            .update()
    }
}
