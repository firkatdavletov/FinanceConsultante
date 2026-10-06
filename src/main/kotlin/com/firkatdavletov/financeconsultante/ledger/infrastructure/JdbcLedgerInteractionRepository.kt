package com.firkatdavletov.financeconsultante.ledger.infrastructure

import com.firkatdavletov.financeconsultante.access.domain.MemberRole
import com.firkatdavletov.financeconsultante.ledger.application.HistoryItem
import com.firkatdavletov.financeconsultante.ledger.application.LedgerInteractionRepository
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryIcon
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import com.firkatdavletov.financeconsultante.ledger.domain.TransactionCategory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Repository
@Transactional(propagation = Propagation.MANDATORY)
class JdbcLedgerInteractionRepository(
    private val jdbc: JdbcClient,
) : LedgerInteractionRepository {
    override fun owned(ownerId: Long): OwnedLedger? =
        jdbc
            .sql(
                """
                SELECT m.household_id, m.id, m.role, a.accounting_started_at FROM member m
                JOIN account a USING (household_id)
                WHERE m.telegram_user_id = ? AND m.revoked_at IS NULL FOR UPDATE OF a, m
                """.trimIndent(),
            ).param(ownerId)
            .query { row, _ ->
                OwnedLedger(
                    row.getObject("household_id", UUID::class.java),
                    row.getObject("id", UUID::class.java),
                    ownerId,
                    row.getObject("accounting_started_at", OffsetDateTime::class.java).toInstant(),
                    MemberRole.valueOf(row.getString("role")),
                )
            }.optional()
            .orElse(null)

    @Suppress("ReturnCount") // Resolve either a persistent UUID or a legacy dictionary identity.
    override fun resolveCategory(
        householdId: UUID,
        key: TransactionCategory,
    ): Category {
        if (key.id != null) {
            return checkNotNull(
                jdbc
                    .sql("SELECT * FROM category WHERE household_id = ? AND id = ?")
                    .params(householdId, key.id)
                    .query { row, _ ->
                        Category(
                            key.id,
                            householdId,
                            row.getString("name"),
                            CategoryKind.valueOf(row.getString("kind")),
                            row.getBoolean("archived"),
                            row.getString("dictionary_key"),
                            CategoryIcon.valueOf(row.getString("icon_code")),
                        )
                    }.optional()
                    .orElse(null),
            ) { "Category is unavailable" }
        }
        findCategory(householdId, key)?.let { return it }
        // Attach dictionary identity to an existing category without changing its ID or archive state.
        jdbc
            .sql(
                """
                INSERT INTO category (id, household_id, name, kind, dictionary_key, icon_code) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (household_id, kind, name) DO UPDATE SET dictionary_key = EXCLUDED.dictionary_key,
                    icon_code = CASE WHEN category.dictionary_key IS NULL THEN EXCLUDED.icon_code ELSE category.icon_code END
                WHERE category.dictionary_key IS NULL OR category.dictionary_key = EXCLUDED.dictionary_key
                """.trimIndent(),
            ).params(
                UUID.randomUUID(),
                householdId,
                key.displayName,
                key.direction.categoryKind.name,
                key.name,
                CategoryIcon.defaultFor(key.name).name,
            ).update()
        return checkNotNull(findCategory(householdId, key))
    }

    private fun findCategory(
        householdId: UUID,
        key: TransactionCategory,
    ): Category? =
        jdbc
            .sql("SELECT * FROM category WHERE household_id = ? AND dictionary_key = ?")
            .params(householdId, key.name)
            .query { row, _ ->
                Category(
                    row.getObject("id", UUID::class.java),
                    householdId,
                    row.getString("name"),
                    CategoryKind.valueOf(row.getString("kind")),
                    row.getBoolean("archived"),
                    row.getString("dictionary_key"),
                    CategoryIcon.valueOf(row.getString("icon_code")),
                )
            }.optional()
            .orElse(null)

    override fun history(
        householdId: UUID,
        before: Long,
        limit: Int,
    ): List<HistoryItem> {
        require(before > 0 && limit in 1..MAX_HISTORY_ROWS)
        return jdbc
            .sql(
                """
                SELECT l.*, c.name AS category_name, actor.telegram_user_id AS actor_telegram_user_id,
                    r.source AS rate_source,
                    r.quoted_at AS rate_quoted_at, r.fetched_at AS rate_fetched_at
                FROM ledger_entry l JOIN category c ON c.id = l.category_id AND c.household_id = l.household_id
                JOIN member actor ON actor.id = l.actor_id AND actor.household_id = l.household_id
                LEFT JOIN daily_exchange_rate r ON r.rate_date = l.occurred_on AND r.rub_kzt = l.rub_kzt
                WHERE l.household_id = ? AND l.history_sequence < ?
                ORDER BY l.history_sequence DESC LIMIT ?
                """.trimIndent(),
            ).params(householdId, before, limit)
            .query { row, index ->
                HistoryItem(
                    row.getLong("history_sequence"),
                    row.getString("category_name"),
                    LedgerRowMapper.mapRow(row, index),
                    row.getLong("actor_telegram_user_id"),
                )
            }.list()
    }

    override fun purchaseHistory(
        householdId: UUID,
        before: Long,
        limit: Int,
    ): List<HistoryItem> {
        require(before > 0 && limit in 1..MAX_PURCHASE_HISTORY_ROWS)
        return jdbc
            .sql(
                """
                SELECT l.*, c.name AS category_name, r.source AS rate_source,
                    r.quoted_at AS rate_quoted_at, r.fetched_at AS rate_fetched_at
                FROM ledger_entry l JOIN category c ON c.id = l.category_id AND c.household_id = l.household_id
                LEFT JOIN daily_exchange_rate r ON r.rate_date = l.occurred_on AND r.rub_kzt = l.rub_kzt
                WHERE l.household_id = ? AND l.direction = 'EXPENSE' AND l.history_sequence < ?
                ORDER BY l.history_sequence DESC LIMIT ?
                """.trimIndent(),
            ).params(householdId, before, limit)
            .query { row, index ->
                HistoryItem(
                    row.getLong("history_sequence"),
                    row.getString("category_name"),
                    LedgerRowMapper.mapRow(row, index),
                )
            }.list()
    }

    companion object {
        private const val MAX_HISTORY_ROWS = 11
        private const val MAX_PURCHASE_HISTORY_ROWS = 51
    }
}
