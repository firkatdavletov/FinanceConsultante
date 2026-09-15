package com.firkatdavletov.financeconsultante.ledger.infrastructure

import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.ledger.application.TransactionDraftRepository
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.ledger.domain.DraftState
import com.firkatdavletov.financeconsultante.ledger.domain.MessageParseResult.RecordTransaction
import com.firkatdavletov.financeconsultante.ledger.domain.TransactionCategory
import com.firkatdavletov.financeconsultante.ledger.domain.TransactionDraft
import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

@Repository
@Transactional(propagation = Propagation.MANDATORY)
class JdbcTransactionDraftRepository(
    private val jdbc: JdbcClient,
) : TransactionDraftRepository {
    override fun active(
        owner: OwnedLedger,
        botId: Long,
    ): List<TransactionDraft> =
        query(
            owner,
            botId,
            "AND state IN ('NEEDS_INPUT', 'READY', 'WAITING_RATE') ORDER BY source_inbox_id",
        ).query(MAPPER)
            .list()

    override fun needsInput(
        owner: OwnedLedger,
        botId: Long,
    ): TransactionDraft? =
        query(owner, botId, "AND state = 'NEEDS_INPUT'")
            .query(MAPPER)
            .optional()
            .orElse(null)

    override fun find(
        owner: OwnedLedger,
        botId: Long,
        id: UUID,
    ): TransactionDraft? =
        query(owner, botId, "AND id = :id")
            .param("id", id)
            .query(MAPPER)
            .optional()
            .orElse(null)

    private fun query(
        owner: OwnedLedger,
        botId: Long,
        condition: String,
    ): JdbcClient.StatementSpec =
        jdbc
            .sql(
                """
                SELECT * FROM transaction_draft WHERE household_id = :household AND member_id = :member
                AND owner_id = :owner AND bot_id = :bot $condition FOR UPDATE
                """.trimIndent(),
            ).param("household", owner.householdId)
            .param("member", owner.memberId)
            .param("owner", owner.ownerId)
            .param("bot", botId)

    override fun save(
        owner: OwnedLedger,
        botId: Long,
        draft: TransactionDraft,
    ) {
        val changed =
            jdbc
                .sql(
                    """
                    INSERT INTO transaction_draft (
                        id, household_id, member_id, owner_id, bot_id, source_inbox_id, state, version,
                        amount, currency, category_key, occurred_on, reason, entry_id, category_name, category_direction,
                        category_id, normalized_description
                    ) VALUES (:id, :household, :member, :owner, :bot, :source, :state, :version,
                        :amount, :currency, :category, :date, :reason, :entry, :name, :direction, :categoryId, :description)
                    ON CONFLICT (id) DO UPDATE SET state = EXCLUDED.state, version = EXCLUDED.version,
                        amount = EXCLUDED.amount, currency = EXCLUDED.currency, category_key = EXCLUDED.category_key,
                        category_name = EXCLUDED.category_name, category_direction = EXCLUDED.category_direction,
                        category_id = EXCLUDED.category_id, occurred_on = EXCLUDED.occurred_on,
                        reason = EXCLUDED.reason, entry_id = EXCLUDED.entry_id,
                        normalized_description = EXCLUDED.normalized_description
                    WHERE transaction_draft.version = EXCLUDED.version - 1
                        AND transaction_draft.state IN ('NEEDS_INPUT', 'READY', 'WAITING_RATE')
                        AND transaction_draft.household_id = EXCLUDED.household_id
                        AND transaction_draft.member_id = EXCLUDED.member_id AND transaction_draft.bot_id = EXCLUDED.bot_id
                        AND transaction_draft.source_inbox_id = EXCLUDED.source_inbox_id
                    """.trimIndent(),
                ).param("id", draft.id)
                .param("household", owner.householdId)
                .param("member", owner.memberId)
                .param("owner", owner.ownerId)
                .param("bot", botId)
                .param("source", draft.sourceInboxId)
                .param("state", draft.state.name)
                .param("version", draft.version)
                .param("amount", draft.proposal?.amount?.amount)
                .param(
                    "currency",
                    draft.proposal
                        ?.amount
                        ?.currency
                        ?.name,
                ).param("category", draft.proposal?.category?.name)
                .param("name", draft.proposal?.category?.displayName)
                .param("direction", draft.proposal?.direction?.name)
                .param("categoryId", draft.proposal?.category?.id)
                .param("date", draft.proposal?.occurredOn)
                .param("description", draft.proposal?.normalizedDescription)
                .param("reason", draft.reason)
                .param("entry", draft.entryId)
                .update()
        check(changed == 1) { "Draft version conflict" }
    }

    companion object {
        private val MAPPER =
            RowMapper { row, _ ->
                TransactionDraft(
                    row.getObject("id", UUID::class.java),
                    row.getLong("source_inbox_id"),
                    DraftState.valueOf(row.getString("state")),
                    row.getLong("version"),
                    row.getBigDecimal("amount")?.let {
                        RecordTransaction(
                            Money.of(it, Currency.valueOf(row.getString("currency"))),
                            TransactionCategory(
                                row.getString("category_key"),
                                row.getString("category_name"),
                                Direction.valueOf(row.getString("category_direction")),
                                row.getObject("category_id", UUID::class.java),
                            ),
                            row.getObject("occurred_on", LocalDate::class.java),
                            row.getString("normalized_description"),
                        )
                    },
                    row.getString("reason"),
                    row.getObject("entry_id", UUID::class.java),
                )
            }
    }
}
