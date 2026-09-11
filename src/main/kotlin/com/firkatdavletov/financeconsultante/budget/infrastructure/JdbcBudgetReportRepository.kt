package com.firkatdavletov.financeconsultante.budget.infrastructure

import com.firkatdavletov.financeconsultante.budget.application.BudgetReportRepository
import com.firkatdavletov.financeconsultante.budget.application.BudgetSettingsView
import com.firkatdavletov.financeconsultante.budget.domain.DailyBudgetSnapshot
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@Repository
@Transactional(propagation = Propagation.MANDATORY)
class JdbcBudgetReportRepository(
    private val jdbc: JdbcClient,
) : BudgetReportRepository {
    override fun settings(
        owner: OwnedLedger,
        lock: Boolean,
    ): BudgetSettingsView =
        jdbc
            .sql(
                """
                SELECT h.cash_reserve, h.settings_version FROM household h
                JOIN account a ON a.household_id = h.id
                JOIN member m ON m.household_id = h.id
                WHERE h.id = :household AND m.id = :member AND m.role = 'OWNER'
                ${if (lock) "FOR UPDATE OF a, h" else ""}
                """.trimIndent(),
            ).param("household", owner.householdId)
            .param("member", owner.memberId)
            .query { row, _ ->
                BudgetSettingsView(Money.of(row.getBigDecimal("cash_reserve")), row.getLong("settings_version"))
            }.single()

    override fun ledgerRevision(householdId: UUID): Long =
        jdbc
            .sql("SELECT COALESCE(MAX(id), 0) FROM entry_revision WHERE household_id = ?")
            .param(householdId)
            .query(Long::class.java)
            .single()

    override fun snapshot(
        householdId: UUID,
        date: LocalDate,
    ): DailyBudgetSnapshot? =
        jdbc
            .sql("SELECT * FROM daily_budget_snapshot WHERE household_id = ? AND snapshot_date = ?")
            .params(householdId, date)
            .query { row, _ ->
                DailyBudgetSnapshot(
                    row.getObject("household_id", UUID::class.java),
                    row.getObject("snapshot_date", LocalDate::class.java),
                    row.getObject("as_of", OffsetDateTime::class.java).toInstant(),
                    Money.of(row.getBigDecimal("balance")),
                    Money.of(row.getBigDecimal("remaining_obligations")),
                    Money.of(row.getBigDecimal("protected_reserve")),
                    Money.of(row.getBigDecimal("goal_reserve")),
                    Money.of(row.getBigDecimal("free_amount")),
                    row.getInt("days_remaining"),
                    Money.of(row.getBigDecimal("original_daily_limit")),
                    row.getLong("ledger_revision"),
                    row.getLong("settings_version"),
                    row.getBoolean("recalculated"),
                )
            }.optional()
            .orElse(null)

    override fun save(snapshot: DailyBudgetSnapshot) {
        jdbc
            .sql(
                """
                INSERT INTO daily_budget_snapshot(
                    household_id, snapshot_date, as_of, balance, remaining_obligations, protected_reserve,
                    goal_reserve, free_amount, days_remaining, original_daily_limit, ledger_revision,
                    settings_version, recalculated
                ) VALUES (
                    :household, :date, :asOf, :balance, :obligations, :reserve,
                    :goals, :free, :days, :dailyLimit, :ledgerRevision, :settingsVersion, :recalculated
                ) ON CONFLICT (household_id, snapshot_date) DO UPDATE SET
                    as_of = EXCLUDED.as_of, balance = EXCLUDED.balance,
                    remaining_obligations = EXCLUDED.remaining_obligations,
                    protected_reserve = EXCLUDED.protected_reserve, goal_reserve = EXCLUDED.goal_reserve,
                    free_amount = EXCLUDED.free_amount, days_remaining = EXCLUDED.days_remaining,
                    original_daily_limit = EXCLUDED.original_daily_limit,
                    ledger_revision = EXCLUDED.ledger_revision, settings_version = EXCLUDED.settings_version,
                    recalculated = EXCLUDED.recalculated
                """.trimIndent(),
            ).param("household", snapshot.householdId)
            .param("date", snapshot.date)
            .param("asOf", snapshot.asOf.atOffset(java.time.ZoneOffset.UTC))
            .param("balance", snapshot.balance.amount)
            .param("obligations", snapshot.remainingObligations.amount)
            .param("reserve", snapshot.protectedReserve.amount)
            .param("goals", snapshot.goalReserve.amount)
            .param("free", snapshot.freeAmount.amount)
            .param("days", snapshot.daysRemaining)
            .param("dailyLimit", snapshot.originalDailyLimit.amount)
            .param("ledgerRevision", snapshot.ledgerRevision)
            .param("settingsVersion", snapshot.settingsVersion)
            .param("recalculated", snapshot.recalculated)
            .update()
    }

    override fun ordinarySpendingAfter(snapshot: DailyBudgetSnapshot): Money =
        Money.of(
            jdbc
                .sql(ORDINARY_SPENDING)
                .param("household", snapshot.householdId)
                .param("revision", snapshot.ledgerRevision)
                .param("date", snapshot.date)
                .query(java.math.BigDecimal::class.java)
                .single(),
            Currency.KZT,
        )

    override fun hasInvalidatingLedgerChanges(snapshot: DailyBudgetSnapshot): Boolean =
        jdbc
            .sql(INVALIDATING_CHANGES)
            .param("household", snapshot.householdId)
            .param("revision", snapshot.ledgerRevision)
            .param("date", snapshot.date)
            .query(Boolean::class.java)
            .single()

    companion object {
        private val NEW_ORDINARY_EXPENSE =
            """
            l.direction = 'EXPENSE'
            AND l.occurred_on = :date
            AND NOT EXISTS (
                SELECT 1 FROM recurring_occurrence_payment p
                WHERE p.household_id = l.household_id AND p.ledger_entry_id = l.id
            )
            """.trimIndent()

        private val REFUND_OF_NEW_ORDINARY_EXPENSE =
            """
            l.direction = 'EXPENSE_REFUND' AND EXISTS (
                SELECT 1 FROM ledger_entry original
                JOIN entry_revision original_created
                    ON original_created.household_id = original.household_id
                    AND original_created.entry_id = original.id
                    AND original_created.version = 0 AND original_created.action = 'CREATED'
                WHERE original.household_id = l.household_id AND original.id = l.refund_of
                    AND original_created.id > :revision AND original.occurred_on = :date
                    AND NOT EXISTS (
                        SELECT 1 FROM recurring_occurrence_payment p
                        WHERE p.household_id = original.household_id AND p.ledger_entry_id = original.id
                    )
            )
            """.trimIndent()

        private val ORDINARY_SPENDING =
            """
            SELECT COALESCE(SUM(
                CASE WHEN l.direction = 'EXPENSE' THEN l.amount_kzt ELSE -l.amount_kzt END
            ), 0)
            FROM ledger_entry l
            JOIN entry_revision created ON created.household_id = l.household_id AND created.entry_id = l.id
                AND created.version = 0 AND created.action = 'CREATED'
            WHERE l.household_id = :household AND created.id > :revision AND l.status = 'POSTED'
                AND (($NEW_ORDINARY_EXPENSE) OR ($REFUND_OF_NEW_ORDINARY_EXPENSE))
            """.trimIndent()

        private val INVALIDATING_CHANGES =
            """
            SELECT EXISTS (
                SELECT 1 FROM entry_revision revision
                JOIN ledger_entry l ON l.household_id = revision.household_id AND l.id = revision.entry_id
                WHERE revision.household_id = :household AND revision.id > :revision
                    AND (
                        revision.action <> 'CREATED'
                        OR l.direction = 'INCOME'
                        OR l.occurred_on <> :date
                        OR (l.direction = 'EXPENSE_REFUND' AND NOT ($REFUND_OF_NEW_ORDINARY_EXPENSE))
                    )
            )
            """.trimIndent()
    }
}
