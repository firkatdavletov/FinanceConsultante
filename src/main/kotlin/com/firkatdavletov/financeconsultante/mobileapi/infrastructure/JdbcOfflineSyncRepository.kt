package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import com.firkatdavletov.financeconsultante.mobileapi.application.DASHBOARD_SYNC_PROTOCOL
import com.firkatdavletov.financeconsultante.mobileapi.application.MobileOperationReceipt
import com.firkatdavletov.financeconsultante.mobileapi.application.OfflineSyncRepository
import com.firkatdavletov.financeconsultante.mobileapi.application.SyncBudgetSettings
import com.firkatdavletov.financeconsultante.mobileapi.application.SyncCategory
import com.firkatdavletov.financeconsultante.mobileapi.application.SyncChange
import com.firkatdavletov.financeconsultante.mobileapi.application.SyncLedger
import com.firkatdavletov.financeconsultante.mobileapi.application.SyncPlanRecord
import com.firkatdavletov.financeconsultante.mobileapi.application.SyncSavingsGoal
import com.firkatdavletov.financeconsultante.mobileapi.application.SyncVariableSpendingExclusion
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@Repository
@Suppress("TooManyFunctions") // One repository serves three compatible journal protocols and their receipts.
class JdbcOfflineSyncRepository(
    private val jdbc: JdbcClient,
) : OfflineSyncRepository {
    private val mapper = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()

    override fun revision(householdId: UUID): Long =
        jdbc
            .sql(
                "SELECT revision FROM mobile_sync_state WHERE household_id = ?",
            ).param(householdId)
            .query(Long::class.java)
            .optional()
            .orElse(0L)

    override fun changes(
        householdId: UUID,
        memberId: UUID,
        after: Long,
        through: Long,
        limit: Int,
    ): List<SyncChange> = queryChanges(householdId, memberId, after, through, limit, 1)

    override fun extendedChanges(
        householdId: UUID,
        memberId: UUID,
        after: Long,
        through: Long,
        limit: Int,
    ): List<SyncChange> = queryChanges(householdId, memberId, after, through, limit, 2)

    override fun dashboardChanges(
        householdId: UUID,
        memberId: UUID,
        after: Long,
        through: Long,
        limit: Int,
    ): List<SyncChange> = queryChanges(householdId, memberId, after, through, limit, DASHBOARD_SYNC_PROTOCOL)

    private fun queryChanges(
        householdId: UUID,
        memberId: UUID,
        after: Long,
        through: Long,
        limit: Int,
        protocolVersion: Int,
    ): List<SyncChange> =
        jdbc
            .sql(
                """
                SELECT c.*, r.request_id FROM mobile_sync_change c
                LEFT JOIN mobile_operation_request r ON r.household_id = c.household_id
                    AND r.member_id = :member AND r.entry_id = (c.snapshot ->> 'id')::uuid AND c.kind = 'LEDGER'
                WHERE c.household_id = :household AND c.revision > :after AND c.revision <= :through
                    AND (c.kind IN ('LEDGER', 'CATEGORY')
                        OR (:protocol >= 2 AND c.kind IN ('RECURRING_RULE', 'RECURRING_OCCURRENCE',
                            'EXPECTED_INCOME', 'OCCURRENCE_PAYMENT', 'INCOME_MATCH'))
                        OR :protocol = 3)
                ORDER BY c.revision LIMIT :limit
                """.trimIndent(),
            ).param("household", householdId)
            .param("member", memberId)
            .param("after", after)
            .param("through", through)
            .param("limit", limit)
            .param("protocol", protocolVersion)
            .query { rs, _ -> mapChange(rs) }
            .list()

    private fun mapChange(rs: ResultSet): SyncChange {
        val node = mapper.readTree(rs.getString("snapshot"))
        val kind = rs.getString("kind")
        return SyncChange(
            rs.getLong("revision"),
            kind,
            rs.getBoolean("deleted"),
            operation =
                if (kind ==
                    "LEDGER"
                ) {
                    ledger(node, rs.getObject("request_id", UUID::class.java))
                } else {
                    null
                },
            category = if (kind == "CATEGORY") category(node) else null,
            record = if (kind in PLAN_KINDS) plan(kind, node) else null,
            budgetSettings =
                if (kind == "BUDGET_SETTINGS") {
                    SyncBudgetSettings(
                        node["id"].asString(),
                        node["opening_balance"].decimalValue().setScale(2).toPlainString(),
                        node["protected_reserve"].decimalValue().setScale(2).toPlainString(),
                    )
                } else {
                    null
                },
            savingsGoal =
                if (kind == "SAVINGS_GOAL") {
                    SyncSavingsGoal(
                        node["id"].asString(),
                        node["allocated_amount"].decimalValue().setScale(2).toPlainString(),
                    )
                } else {
                    null
                },
            variableSpendingExclusion =
                if (kind == "VARIABLE_SPENDING_EXCLUSION") {
                    SyncVariableSpendingExclusion(node["id"].asString())
                } else {
                    null
                },
        )
    }

    override fun receipt(
        householdId: UUID,
        memberId: UUID,
        requestId: UUID,
    ): MobileOperationReceipt? =
        jdbc
            .sql(
                "SELECT payload_hash, entry_id FROM mobile_operation_request " +
                    "WHERE household_id = ? AND member_id = ? AND request_id = ?",
            ).params(householdId, memberId, requestId)
            .query { rs, _ ->
                MobileOperationReceipt(rs.getString("payload_hash"), rs.getObject("entry_id", UUID::class.java))
            }.optional()
            .orElse(null)

    override fun remember(
        householdId: UUID,
        memberId: UUID,
        requestId: UUID,
        payloadHash: String,
        entryId: UUID,
    ) {
        jdbc
            .sql(
                "INSERT INTO mobile_operation_request(household_id, member_id, request_id, payload_hash, entry_id) " +
                    "VALUES (?, ?, ?, ?, ?)",
            ).params(householdId, memberId, requestId, payloadHash, entryId)
            .update()
    }

    override fun operation(
        householdId: UUID,
        memberId: UUID,
        entryId: UUID,
    ): SyncLedger =
        jdbc
            .sql(
                """
                SELECT to_jsonb(l)::text AS snapshot, r.request_id FROM ledger_entry l
                LEFT JOIN mobile_operation_request r ON r.household_id = l.household_id
                    AND r.member_id = ? AND r.entry_id = l.id
                WHERE l.household_id = ? AND l.id = ?
                """.trimIndent(),
            ).params(memberId, householdId, entryId)
            .query { rs, _ ->
                ledger(mapper.readTree(rs.getString("snapshot")), rs.getObject("request_id", UUID::class.java))
            }.single()

    private fun ledger(
        node: JsonNode,
        requestId: UUID?,
    ): SyncLedger =
        SyncLedger(
            id = UUID.fromString(node["id"].asString()),
            categoryId = UUID.fromString(node["category_id"].asString()),
            type = node["direction"].asString(),
            originalAmount = node["original_amount"].decimalValue().toPlainString(),
            originalCurrency = node["original_currency"].asString(),
            amountKzt = node["amount_kzt"].decimalValue().toPlainString(),
            occurredOn = LocalDate.parse(node["occurred_on"].asString()),
            createdAt = timestamp(node["created_at"].asString()),
            status = node["status"].asString(),
            version = node["version"].asLong(),
            sequence = node["history_sequence"].asLong(),
            description = node["normalized_description"]?.takeUnless { it.isNull }?.asString(),
            refundOf = node["refund_of"]?.takeUnless { it.isNull }?.asString()?.let(UUID::fromString),
            requestId = requestId,
            occurredAt = timestamp(node["occurred_at"].asString()),
        )

    private fun category(node: JsonNode): SyncCategory {
        val icon = node["icon_code"].asString()
        return SyncCategory(
            UUID.fromString(node["id"].asString()),
            node["name"].asString(),
            node["kind"].asString(),
            icon,
            "📦",
            node["archived"].asBoolean(),
            node["dictionary_key"]?.takeUnless { it.isNull }?.asString(),
        )
    }

    private fun plan(
        kind: String,
        node: JsonNode,
    ): SyncPlanRecord {
        fun string(key: String) = node[key]?.takeUnless { it.isNull }?.asString()

        fun date(key: String) = string(key)?.let(LocalDate::parse)
        val link = kind in setOf("OCCURRENCE_PAYMENT", "INCOME_MATCH")
        val id =
            if (link) {
                string(
                    if (kind ==
                        "OCCURRENCE_PAYMENT"
                    ) {
                        "occurrence_id"
                    } else {
                        "expected_income_id"
                    },
                )
            } else {
                string("id")
            }
        return SyncPlanRecord(
            id = requireNotNull(id),
            categoryId = string("category_id"),
            ruleId = string("rule_id"),
            ledgerId = string("ledger_entry_id"),
            amount = (node["amount"] ?: node["planned_amount"])?.decimalValue()?.toPlainString(),
            frequency = string("frequency"),
            scheduleValue = node["schedule_value"]?.takeUnless { it.isNull }?.asInt(),
            startsOn = date("starts_on"),
            active = node["active"]?.asBoolean(),
            dueOn = date("due_on"),
            status = string("status"),
            version = node["version"]?.asLong(),
            createdAt = string("created_at")?.let(::timestamp),
            modifiedAt = string("updated_at")?.let(::timestamp),
        )
    }

    companion object {
        private val PLAN_KINDS =
            setOf(
                "RECURRING_RULE",
                "RECURRING_OCCURRENCE",
                "EXPECTED_INCOME",
                "OCCURRENCE_PAYMENT",
                "INCOME_MATCH",
            )
    }

    private fun timestamp(value: String): Instant = OffsetDateTime.parse(value).toInstant()
}
