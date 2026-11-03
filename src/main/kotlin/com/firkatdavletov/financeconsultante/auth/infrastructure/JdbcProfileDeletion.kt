package com.firkatdavletov.financeconsultante.auth.infrastructure

import com.firkatdavletov.financeconsultante.auth.application.ProfileDeletionFailure
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class JdbcProfileDeletion(
    private val jdbc: JdbcClient,
    private val access: JdbcFirebaseAccountAccess,
) {
    fun status(hash: String): String? =
        jdbc
            .sql("SELECT status FROM firebase_profile_deletion WHERE token_hash=?")
            .param(hash)
            .query(String::class.java)
            .optional()
            .orElse(null)

    @Transactional
    fun accept(
        uid: String,
        hash: String,
    ): String {
        access.lock("profile:$uid", false)
        status(hash)?.let { return it }
        if (jdbc
                .sql("SELECT EXISTS(SELECT 1 FROM firebase_profile_deletion WHERE uid_hash=?)")
                .param(deletionHash(uid))
                .query(Boolean::class.java)
                .single()
        ) {
            throw ProfileDeletionFailure(
                "account_deletion_in_progress",
                org.springframework.http.HttpStatus.CONFLICT
                    .value(),
            )
        }
        val budgets =
            jdbc
                .sql(
                    """
                    SELECT DISTINCT household_id FROM firebase_budget_access WHERE firebase_uid=:uid
                    UNION SELECT household_id FROM firebase_budget_invitation WHERE accepted_by_uid=:uid
                    ORDER BY household_id
                    """.trimIndent(),
                ).param("uid", uid)
                .query(UUID::class.java)
                .list()
        budgets.forEach { access.lock("profile-budget:$it", false) }
        val owned =
            jdbc
                .sql(
                    """
                    SELECT b.household_id FROM firebase_budget_access b JOIN member m ON m.id=b.member_id
                    WHERE b.firebase_uid=? AND m.role='OWNER' ORDER BY b.household_id
                    """.trimIndent(),
                ).param(uid)
                .query(UUID::class.java)
                .list()
        // Snapshot member generations before removing invitation receipts and current bindings.
        val members =
            jdbc
                .sql(
                    """
                    SELECT member_id FROM firebase_budget_access WHERE firebase_uid=:uid
                    UNION SELECT accepted_member_id FROM firebase_budget_invitation WHERE accepted_by_uid=:uid
                    """.trimIndent(),
                ).param("uid", uid)
                .query(UUID::class.java)
                .list()
        owned.forEach {
            jdbc
                .sql("SELECT erase_firebase_budget(?)")
                .param(it)
                .query { _, _ -> Unit }
                .single()
        }
        members.filterNotNull().forEach { eraseMembership(it) }
        finishProfile(uid, hash)
        return "PENDING"
    }

    private fun finishProfile(
        uid: String,
        hash: String,
    ) {
        jdbc
            .sql(
                """
                UPDATE firebase_budget_invitation SET accepted_by_uid=NULL, accepted_member_id=NULL WHERE accepted_by_uid=?
                """.trimIndent(),
            ).param(uid)
            .update()
        jdbc.sql("DELETE FROM firebase_budget_creation WHERE firebase_uid=?").param(uid).update()
        jdbc.sql("DELETE FROM firebase_budget_access WHERE firebase_uid=?").param(uid).update()
        jdbc.sql("DELETE FROM firebase_user WHERE firebase_uid=?").param(uid).update()
        jdbc
            .sql("INSERT INTO firebase_profile_deletion(uid_hash, token_hash, firebase_uid) VALUES (?, ?, ?)")
            .params(deletionHash(uid), hash, uid)
            .update()
    }

    private fun eraseMembership(member: UUID) {
        // Financial author UUIDs remain, while personal work and Telegram bindings are erased.
        jdbc
            .sql(
                "DELETE FROM telegram_outbox WHERE member_id=? OR " +
                    "report_job_id IN (SELECT id FROM daily_report_job WHERE member_id=?)",
            ).params(member, member)
            .update()
        for (table in listOf(
            "openai_recognition_job",
            "transaction_draft",
            "telegram_entry_correction_dialog",
            "daily_report_job",
        )) {
            jdbc.sql("DELETE FROM $table WHERE member_id=?").param(member).update()
        }
        jdbc
            .sql(
                "DELETE FROM telegram_category_dialog WHERE " +
                    "owner_id IN (SELECT telegram_user_id FROM member WHERE id=?)",
            ).param(member)
            .update()
        jdbc
            .sql("DELETE FROM telegram_dialog WHERE owner_id IN (SELECT telegram_user_id FROM member WHERE id=?)")
            .param(member)
            .update()
        jdbc.sql("DELETE FROM telegram_inbox WHERE member_id=?").param(member).update()
        jdbc
            .sql(
                "UPDATE member SET revoked_at=COALESCE(revoked_at, now()), reports_enabled=FALSE, " +
                    "telegram_user_id=NULL WHERE id=? AND role='MEMBER'",
            ).param(member)
            .update()
    }
}
