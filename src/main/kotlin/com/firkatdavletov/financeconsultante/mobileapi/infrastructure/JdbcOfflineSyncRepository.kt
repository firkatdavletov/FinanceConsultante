package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import com.firkatdavletov.financeconsultante.ledger.domain.CategoryIcon
import com.firkatdavletov.financeconsultante.mobileapi.application.MobileOperationReceipt
import com.firkatdavletov.financeconsultante.mobileapi.application.OfflineSyncRepository
import com.firkatdavletov.financeconsultante.mobileapi.application.SyncCategory
import com.firkatdavletov.financeconsultante.mobileapi.application.SyncChange
import com.firkatdavletov.financeconsultante.mobileapi.application.SyncLedger
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@Repository
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
    ): List<SyncChange> =
        jdbc
            .sql(
                """
                SELECT c.*, r.request_id FROM mobile_sync_change c
                LEFT JOIN mobile_operation_request r ON r.household_id = c.household_id
                    AND r.member_id = :member AND r.entry_id = (c.snapshot ->> 'id')::uuid AND c.kind = 'LEDGER'
                WHERE c.household_id = :household AND c.revision > :after AND c.revision <= :through
                ORDER BY c.revision LIMIT :limit
                """.trimIndent(),
            ).param("household", householdId)
            .param("member", memberId)
            .param("after", after)
            .param("through", through)
            .param("limit", limit)
            .query { rs, _ ->
                val node = mapper.readTree(rs.getString("snapshot"))
                val kind = rs.getString("kind")
                SyncChange(
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
                )
            }.list()

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
        )

    private fun category(node: JsonNode): SyncCategory {
        val icon = CategoryIcon.valueOf(node["icon_code"].asString())
        return SyncCategory(
            UUID.fromString(node["id"].asString()),
            node["name"].asString(),
            node["kind"].asString(),
            icon.name,
            icon.emoji,
            node["archived"].asBoolean(),
        )
    }

    private fun timestamp(value: String): Instant = OffsetDateTime.parse(value).toInstant()
}
