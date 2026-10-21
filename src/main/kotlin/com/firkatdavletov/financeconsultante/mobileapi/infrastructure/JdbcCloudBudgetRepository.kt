package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import com.firkatdavletov.financeconsultante.access.domain.MemberRole
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetActor
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetRepository
import com.firkatdavletov.financeconsultante.mobileapi.application.CreateCloudBudget
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

@Repository
class JdbcCloudBudgetRepository(
    private val jdbc: JdbcClient,
    private val properties: MobileApiProperties,
    private val bootstrap: JdbcCloudBudgetBootstrap,
) : CloudBudgetRepository {
    override fun lock(tokenHash: String) {
        jdbc
            .sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
            .param(tokenHash)
            .query {
                _,
                _,
                ->
                true
            }.single()
    }

    override fun binding(tokenHash: String): CloudBudgetActor? =
        jdbc
            .sql(
                """
SELECT m.household_id, m.id, m.role, a.accounting_started_at, b.create_request_id,
b.create_payload_hash FROM mobile_budget_access b JOIN member m ON m.household_id =
b.household_id AND m.id = b.member_id JOIN account a ON a.household_id = m.household_id WHERE
b.token_hash = ? AND m.revoked_at IS NULL
                """.trimIndent(),
            ).param(tokenHash)
            .query { row, _ ->
                CloudBudgetActor(
                    row.getObject("household_id", UUID::class.java),
                    row.getObject("id", UUID::class.java),
                    row.getObject("accounting_started_at", OffsetDateTime::class.java).toInstant(),
                    MemberRole.valueOf(row.getString("role")),
                    row.getObject("create_request_id", UUID::class.java),
                    row.getString("create_payload_hash"),
                )
            }.optional()
            .orElse(null)

    override fun legacyUserId(tokenHash: String): Long? =
        jdbc
            .sql(
                """
SELECT m.telegram_user_id FROM mobile_budget_access b JOIN member m ON m.household_id =
b.household_id AND m.id = b.member_id WHERE b.token_hash = ? AND m.revoked_at IS NULL AND
m.telegram_user_id IS NOT NULL
                """.trimIndent(),
            ).param(tokenHash)
            .query(Long::class.javaObjectType)
            .optional()
            .orElse(null)

    override fun importLegacy(tokenHash: String) {
        if (properties.telegramUserId <= 0) return
        jdbc
            .sql(
                """
INSERT INTO mobile_budget_access(token_hash, household_id, member_id) SELECT ?, m.household_id,
m.id FROM member m JOIN account a ON a.household_id = m.household_id WHERE m.telegram_user_id =
? AND m.revoked_at IS NULL ON CONFLICT DO NOTHING
                """.trimIndent(),
            ).params(tokenHash, properties.telegramUserId)
            .update()
    }

    override fun create(
        tokenHash: String,
        request: CreateCloudBudget,
        payloadHash: String,
    ) {
        bootstrap.create(request)
        jdbc
            .sql(
                """
INSERT INTO mobile_budget_access(token_hash, household_id, member_id, create_request_id,
create_payload_hash) VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).params(tokenHash, request.householdId, request.memberId, request.requestId, payloadHash)
            .update()
    }

    override fun restoreRule(
        householdId: UUID,
        id: UUID,
        effectiveOn: LocalDate,
    ) {
        val restored =
            jdbc
                .sql(
                    """
UPDATE recurring_rule SET active = TRUE, version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE household_id = ? AND id = ? AND NOT active
                    """.trimIndent(),
                ).params(householdId, id)
                .update()
        if (restored == 0) return
        jdbc
            .sql(
                """
UPDATE recurring_occurrence SET status = 'SCHEDULED', version = version + 1, updated_at =
CURRENT_TIMESTAMP WHERE household_id = ? AND rule_id = ? AND due_on >= ? AND status =
'CANCELLED'
                """.trimIndent(),
            ).params(householdId, id, effectiveOn)
            .update()
    }

    override fun restoreOperation(
        householdId: UUID,
        memberId: UUID,
        id: UUID,
        source: String,
        at: java.time.Instant,
    ) {
        val changed =
            jdbc
                .sql(
                    """
                    UPDATE ledger_entry SET status = 'POSTED', version = version + 1, modified_at = ?
                    WHERE household_id = ? AND id = ? AND status = 'VOIDED'
                    """.trimIndent(),
                ).params(at.atOffset(java.time.ZoneOffset.UTC), householdId, id)
                .update()
        if (changed > 0) {
            jdbc
                .sql(
                    """
                    INSERT INTO entry_revision(household_id, entry_id, version, actor_id, action,
                        snapshot, created_at, reason, mutation_source)
                    SELECT household_id, id, version, ?, 'CORRECTED', to_jsonb(l), modified_at,
                        'LOCAL_STATE_RESTORED', ? FROM ledger_entry l WHERE household_id = ? AND id = ?
                    """.trimIndent(),
                ).params(memberId, source, householdId, id)
                .update()
        }
    }

    override fun restoreIncome(
        householdId: UUID,
        id: UUID,
    ) {
        jdbc
            .sql(
                """
UPDATE expected_income SET status = 'SCHEDULED', version = version + 1, updated_at =
CURRENT_TIMESTAMP WHERE household_id = ? AND id = ? AND status = 'CANCELLED'
                """.trimIndent(),
            ).params(householdId, id)
            .update()
    }

    override fun occurrenceId(
        householdId: UUID,
        ruleId: UUID,
        dueOn: LocalDate,
    ): UUID? =
        jdbc
            .sql(
                "SELECT id FROM recurring_occurrence WHERE household_id = ? AND rule_id = ? AND due_on = ?",
            ).params(householdId, ruleId, dueOn)
            .query(UUID::class.java)
            .optional()
            .orElse(null)
}
