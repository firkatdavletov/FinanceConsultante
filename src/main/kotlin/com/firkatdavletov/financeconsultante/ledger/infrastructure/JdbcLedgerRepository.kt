package com.firkatdavletov.financeconsultante.ledger.infrastructure

import com.firkatdavletov.financeconsultante.ledger.application.LedgerRepository
import com.firkatdavletov.financeconsultante.ledger.domain.LedgerEntry
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Types
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JdbcLedgerRepository(
    private val jdbc: JdbcClient,
) : LedgerRepository {
    override fun findBySource(
        householdId: UUID,
        sourceKey: String,
    ): LedgerEntry? =
        jdbc
            .sql(
                "$SELECT_ENTRY WHERE l.household_id = :household AND l.source_key = :source",
            ).param("household", householdId)
            .param("source", sourceKey)
            .query(LedgerRowMapper)
            .optional()
            .orElse(null)

    @Transactional(propagation = Propagation.MANDATORY)
    override fun insert(entry: LedgerEntry): Boolean {
        val inserted =
            jdbc
                .sql(
                    """
                    INSERT INTO ledger_entry (
                        id, household_id, account_id, accounting_started_at, category_id, direction,
                        original_amount, original_currency, amount_kzt, occurred_at, rub_kzt,
                        status, version, source_key, actor_id, created_at
                    ) VALUES (
                        :id, :household, :account, :start, :category, :direction,
                        :original, :currency, :kzt, :occurred, :rate,
                        :status, :version, :source, :actor, :created
                    ) ON CONFLICT (household_id, source_key) DO NOTHING
                    """.trimIndent(),
                ).param("id", entry.id)
                .param("household", entry.householdId)
                .param("account", entry.accountId)
                .param("start", entry.accountingStartedAt.atOffset(ZoneOffset.UTC))
                .param("category", entry.categoryId)
                .param("direction", entry.direction.name)
                .param("original", entry.originalAmount.amount)
                .param("currency", entry.originalAmount.currency.name)
                .param("kzt", entry.amountKzt.amount)
                .param("occurred", entry.occurredAt.atOffset(ZoneOffset.UTC))
                .param("rate", entry.exchangeRate?.rubKzt, Types.NUMERIC)
                .param("status", entry.status.name)
                .param("version", entry.version)
                .param("source", entry.sourceKey)
                .param("actor", entry.actorId)
                .param("created", entry.createdAt.atOffset(ZoneOffset.UTC))
                .update()
        if (inserted == 0) return false
        jdbc
            .sql(
                """
                INSERT INTO entry_revision (household_id, entry_id, version, actor_id, action, snapshot, created_at)
                SELECT household_id, id, version, actor_id, 'CREATED', to_jsonb(l), created_at
                FROM ledger_entry l WHERE id = ?
                """.trimIndent(),
            ).param(entry.id)
            .update()
        return true
    }

    override fun findBefore(
        householdId: UUID,
        untilExclusive: Instant,
    ): List<LedgerEntry> =
        jdbc
            .sql(
                """
                $SELECT_ENTRY
                WHERE l.household_id = :household AND l.occurred_at < :until ORDER BY l.occurred_at, l.id
                """.trimIndent(),
            ).param("household", householdId)
            .param("until", untilExclusive.atOffset(ZoneOffset.UTC))
            .query(LedgerRowMapper)
            .list()

    companion object {
        private val SELECT_ENTRY =
            """
            SELECT l.*, r.source AS rate_source, r.quoted_at AS rate_quoted_at, r.fetched_at AS rate_fetched_at
            FROM ledger_entry l LEFT JOIN daily_exchange_rate r
                ON l.occurred_on = r.rate_date AND l.rub_kzt = r.rub_kzt
            """.trimIndent()
    }
}
