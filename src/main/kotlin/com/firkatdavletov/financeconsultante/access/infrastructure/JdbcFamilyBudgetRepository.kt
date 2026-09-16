package com.firkatdavletov.financeconsultante.access.infrastructure

import com.firkatdavletov.financeconsultante.access.application.FamilyBudgetRepository
import com.firkatdavletov.financeconsultante.access.domain.FamilyInvitation
import com.firkatdavletov.financeconsultante.access.domain.FamilyMember
import com.firkatdavletov.financeconsultante.access.domain.MemberRole
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
@Suppress("TooManyFunctions") // Keeps the cohesive family membership SQL transitions in one adapter.
class JdbcFamilyBudgetRepository(
    private val jdbc: JdbcClient,
) : FamilyBudgetRepository {
    override fun activeMember(telegramUserId: Long): FamilyMember? =
        jdbc
            .sql("$SELECT_MEMBER WHERE telegram_user_id = ? AND revoked_at IS NULL")
            .param(telegramUserId)
            .query(MEMBER_MAPPER)
            .optional()
            .orElse(null)

    override fun activeMember(
        telegramUserId: Long,
        memberId: UUID,
    ): FamilyMember? =
        jdbc
            .sql("$SELECT_MEMBER WHERE telegram_user_id = ? AND id = ? AND revoked_at IS NULL")
            .params(telegramUserId, memberId)
            .query(MEMBER_MAPPER)
            .optional()
            .orElse(null)

    override fun pendingInvitation(telegramUserId: Long): FamilyInvitation? =
        jdbc
            .sql("$SELECT_INVITATION WHERE invited_telegram_user_id = ? AND status = 'PENDING' FOR UPDATE")
            .param(telegramUserId)
            .query(INVITATION_MAPPER)
            .optional()
            .orElse(null)

    override fun members(householdId: UUID): List<FamilyMember> =
        jdbc
            .sql("$SELECT_MEMBER WHERE household_id = ? AND revoked_at IS NULL ORDER BY role, joined_at, id")
            .param(householdId)
            .query(MEMBER_MAPPER)
            .list()

    override fun pendingInvitations(householdId: UUID): List<FamilyInvitation> =
        jdbc
            .sql("$SELECT_INVITATION WHERE household_id = ? AND status = 'PENDING' ORDER BY created_at, id")
            .param(householdId)
            .query(INVITATION_MAPPER)
            .list()

    override fun invitationBySource(
        householdId: UUID,
        source: String,
    ): FamilyInvitation? =
        jdbc
            .sql("$SELECT_INVITATION WHERE household_id = ? AND source_key = ?")
            .params(householdId, source)
            .query(INVITATION_MAPPER)
            .optional()
            .orElse(null)

    override fun createInvitation(
        invitation: FamilyInvitation,
        invitedBy: UUID,
        source: String,
    ): Boolean =
        jdbc
            .sql(
                """
                INSERT INTO family_invitation(
                    id, household_id, invited_telegram_user_id, invited_by, source_key, created_at
                ) SELECT :id, :household, :telegram, :invitedBy, :source, :created
                WHERE EXISTS (
                    SELECT 1 FROM member WHERE household_id = :household AND id = :invitedBy
                        AND role = 'OWNER' AND revoked_at IS NULL
                ) AND NOT EXISTS (
                    SELECT 1 FROM member WHERE telegram_user_id = :telegram AND revoked_at IS NULL
                ) ON CONFLICT DO NOTHING
                """.trimIndent(),
            ).param("id", invitation.id)
            .param("household", invitation.householdId)
            .param("telegram", invitation.invitedTelegramUserId)
            .param("invitedBy", invitedBy)
            .param("source", source)
            .param("created", offset(invitation.createdAt))
            .update() == 1

    override fun acceptInvitation(
        invitation: FamilyInvitation,
        member: FamilyMember,
        acceptedAt: Instant,
    ): Boolean {
        val accepted =
            jdbc
                .sql(
                    """
                    UPDATE family_invitation SET status = 'ACCEPTED', responded_at = :accepted
                    WHERE id = :id AND household_id = :household AND invited_telegram_user_id = :telegram
                        AND status = 'PENDING'
                        AND NOT EXISTS (
                            SELECT 1 FROM member WHERE telegram_user_id = :telegram AND revoked_at IS NULL
                        )
                    """.trimIndent(),
                ).param("accepted", offset(acceptedAt))
                .param("id", invitation.id)
                .param("household", invitation.householdId)
                .param("telegram", invitation.invitedTelegramUserId)
                .update() == 1
        if (!accepted) return false
        jdbc
            .sql(
                """
                INSERT INTO member(
                    id, household_id, telegram_user_id, role, joined_at, reports_enabled
                ) VALUES (?, ?, ?, 'MEMBER', ?, FALSE)
                """.trimIndent(),
            ).params(member.id, member.householdId, member.telegramUserId, offset(member.joinedAt))
            .update()
        return true
    }

    override fun revokeInvitation(
        householdId: UUID,
        telegramUserId: Long,
        revokedAt: Instant,
    ): Boolean =
        jdbc
            .sql(
                """
                UPDATE family_invitation SET status = 'REVOKED', responded_at = ?
                WHERE household_id = ? AND invited_telegram_user_id = ? AND status = 'PENDING'
                """.trimIndent(),
            ).params(offset(revokedAt), householdId, telegramUserId)
            .update() == 1

    override fun revokeMember(
        householdId: UUID,
        telegramUserId: Long,
        revokedAt: Instant,
    ): FamilyMember? =
        jdbc
            .sql(
                """
                UPDATE member SET revoked_at = :revoked, reports_enabled = FALSE
                WHERE household_id = :household AND telegram_user_id = :telegram
                    AND role = 'MEMBER' AND revoked_at IS NULL
                RETURNING *
                """.trimIndent(),
            ).param("revoked", offset(revokedAt))
            .param("household", householdId)
            .param("telegram", telegramUserId)
            .query(MEMBER_MAPPER)
            .optional()
            .orElse(null)

    override fun setReportsEnabled(
        householdId: UUID,
        telegramUserId: Long,
        enabled: Boolean,
    ): FamilyMember? =
        jdbc
            .sql(
                """
                UPDATE member SET reports_enabled = ?
                WHERE household_id = ? AND telegram_user_id = ? AND revoked_at IS NULL
                RETURNING *
                """.trimIndent(),
            ).params(enabled, householdId, telegramUserId)
            .query(MEMBER_MAPPER)
            .optional()
            .orElse(null)

    override fun stopReports(memberId: UUID) {
        jdbc
            .sql(
                """
                UPDATE telegram_outbox SET status = 'FAILED', lease_token = NULL, lease_until = NULL,
                    last_error = 'access_revoked'
                WHERE report_job_id IN (
                    SELECT id FROM daily_report_job WHERE member_id = ?
                ) AND status IN ('PENDING', 'SENDING')
                """.trimIndent(),
            ).param(memberId)
            .update()
        jdbc
            .sql(
                """
                UPDATE daily_report_job SET status = 'FAILED', lease_token = NULL, lease_until = NULL,
                    updated_at = clock_timestamp()
                WHERE member_id = ? AND status IN ('SCHEDULED', 'PROCESSING', 'QUEUED')
                """.trimIndent(),
            ).param(memberId)
            .update()
    }

    private fun offset(value: Instant): OffsetDateTime = value.atOffset(ZoneOffset.UTC)

    private companion object {
        const val SELECT_MEMBER =
            "SELECT id, household_id, telegram_user_id, role, reports_enabled, joined_at FROM member"
        const val SELECT_INVITATION =
            "SELECT id, household_id, invited_telegram_user_id, created_at FROM family_invitation"
        val MEMBER_MAPPER =
            { row: java.sql.ResultSet, _: Int ->
                FamilyMember(
                    row.getObject("id", UUID::class.java),
                    row.getObject("household_id", UUID::class.java),
                    row.getLong("telegram_user_id"),
                    MemberRole.valueOf(row.getString("role")),
                    row.getBoolean("reports_enabled"),
                    row.getObject("joined_at", OffsetDateTime::class.java).toInstant(),
                )
            }
        val INVITATION_MAPPER =
            { row: java.sql.ResultSet, _: Int ->
                FamilyInvitation(
                    row.getObject("id", UUID::class.java),
                    row.getObject("household_id", UUID::class.java),
                    row.getLong("invited_telegram_user_id"),
                    row.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                )
            }
    }
}
