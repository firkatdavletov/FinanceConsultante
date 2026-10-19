package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import com.firkatdavletov.financeconsultante.mobileapi.application.BudgetCommandReceipt
import com.firkatdavletov.financeconsultante.mobileapi.application.BudgetCommandRepository
import com.firkatdavletov.financeconsultante.mobileapi.application.BudgetSyncCommand
import com.firkatdavletov.financeconsultante.mobileapi.application.StoredBudgetCommand
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class JdbcBudgetCommandRepository(
    private val jdbc: JdbcClient,
) : BudgetCommandRepository {
    override fun receipt(
        householdId: UUID,
        memberId: UUID,
        requestId: UUID,
    ): StoredBudgetCommand? =
        jdbc
            .sql("SELECT * FROM mobile_sync_command WHERE household_id = ? AND member_id = ? AND request_id = ?")
            .params(householdId, memberId, requestId)
            .query { rs, _ ->
                StoredBudgetCommand(
                    rs.getString("payload_hash"),
                    BudgetCommandReceipt(
                        requestId,
                        rs.getLong("revision"),
                        rs.getObject("entity_token", Long::class.javaObjectType),
                    ),
                )
            }.optional()
            .orElse(null)

    override fun token(
        householdId: UUID,
        kind: String,
        entityId: UUID,
        lock: Boolean,
    ): Long? {
        val table =
            when (kind) {
                "CATEGORY" -> "category"
                "RECURRING_RULE" -> "recurring_rule"
                "RECURRING_OCCURRENCE" -> "recurring_occurrence"
                "EXPECTED_INCOME" -> "expected_income"
                else -> error("Unsupported entity kind")
            }
        val column = if (kind == "CATEGORY") "0::bigint" else "version"
        val row =
            jdbc
                .sql(
                    "SELECT $column AS token FROM $table WHERE household_id = ? AND id = ?" +
                        if (lock) " FOR UPDATE" else "",
                ).params(householdId, entityId)
                .query(Long::class.java)
                .optional()
                .orElse(null) ?: return null
        return if (kind != "CATEGORY") {
            row
        } else {
            jdbc
                .sql(
                    "SELECT MAX(revision) FROM mobile_sync_change WHERE household_id = ? " +
                        "AND kind = 'CATEGORY' AND snapshot ->> 'id' = ?",
                ).params(householdId, entityId.toString())
                .query(Long::class.java)
                .optional()
                .orElse(null)
        }
    }

    override fun remember(
        command: BudgetSyncCommand,
        hash: String,
        receipt: BudgetCommandReceipt,
    ) {
        jdbc
            .sql(
                "INSERT INTO mobile_sync_command(household_id, member_id, request_id, " +
                    "payload_hash, revision, entity_token) " +
                    "VALUES (:household, :member, :request, :hash, :revision, :token)",
            ).param("household", command.householdId)
            .param("member", command.memberId)
            .param("request", command.requestId)
            .param("hash", hash)
            .param("revision", receipt.revision)
            .param("token", receipt.entityToken, java.sql.Types.BIGINT)
            .update()
    }
}
