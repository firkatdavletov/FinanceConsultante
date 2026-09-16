package com.firkatdavletov.financeconsultante.telegram.infrastructure

import com.firkatdavletov.financeconsultante.ledger.application.AccountingService
import com.firkatdavletov.financeconsultante.ledger.domain.Account
import com.firkatdavletov.financeconsultante.ledger.domain.BudgetSetup
import com.firkatdavletov.financeconsultante.ledger.domain.Household
import com.firkatdavletov.financeconsultante.ledger.domain.Member
import com.firkatdavletov.financeconsultante.shared.domain.Money
import com.firkatdavletov.financeconsultante.telegram.application.OwnedSettings
import com.firkatdavletov.financeconsultante.telegram.application.SetupStore
import com.firkatdavletov.financeconsultante.telegram.domain.ACCOUNTING_ZONE
import com.firkatdavletov.financeconsultante.telegram.domain.BudgetSettings
import com.firkatdavletov.financeconsultante.telegram.domain.SetupDialog
import com.firkatdavletov.financeconsultante.telegram.domain.SetupStep
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.UUID

@Transactional(propagation = Propagation.MANDATORY)
open class JdbcSetupStore(
    private val jdbc: JdbcClient,
    private val accounting: AccountingService,
) : SetupStore {
    override fun dialog(ownerId: Long): SetupDialog {
        jdbc.sql("INSERT INTO telegram_dialog (owner_id) VALUES (?) ON CONFLICT DO NOTHING").param(ownerId).update()
        return jdbc
            .sql("SELECT * FROM telegram_dialog WHERE owner_id = ? FOR UPDATE")
            .param(ownerId)
            .query { row, _ ->
                SetupDialog(
                    SetupStep.valueOf(row.getString("step")),
                    row.getBoolean("initial_setup"),
                    row.getBigDecimal("opening_balance")?.let { Money.of(it) },
                    row.getObject("started_on", LocalDate::class.java),
                    row.getObject("report_time", LocalTime::class.java),
                    Money.of(row.getBigDecimal("cash_reserve")),
                )
            }.single()
    }

    override fun settings(ownerId: Long): OwnedSettings? {
        // The account lock also serializes opening edits with the FK checks of new ledger entries.
        val settings =
            jdbc
                .sql(
                    """
                    SELECT h.report_time, h.cash_reserve, a.opening_balance, a.accounting_started_at
                    FROM member m JOIN household h ON h.id = m.household_id JOIN account a ON
                    a.household_id = h.id
                    WHERE m.telegram_user_id = ? AND m.role = 'OWNER' AND m.revoked_at IS NULL FOR UPDATE OF a, h, m
                    """.trimIndent(),
                ).param(ownerId)
                .query { row, _ ->
                    BudgetSettings(
                        Money.of(row.getBigDecimal("opening_balance")),
                        row
                            .getObject(
                                "accounting_started_at",
                                OffsetDateTime::class.java,
                            ).atZoneSameInstant(ACCOUNTING_ZONE)
                            .toLocalDate(),
                        row.getObject("report_time", LocalTime::class.java),
                        Money.of(row.getBigDecimal("cash_reserve")),
                    )
                }.optional()
                .orElse(null) ?: return null
        val hasEntries =
            jdbc
                .sql(
                    """
                    SELECT EXISTS (SELECT 1 FROM ledger_entry e JOIN member m ON m.household_id =
                    e.household_id
                    WHERE m.telegram_user_id = ?)
                    """.trimIndent(),
                ).param(ownerId)
                .query(Boolean::class.java)
                .single()
        return OwnedSettings(settings, hasEntries)
    }

    override fun saveDialog(
        ownerId: Long,
        dialog: SetupDialog,
    ) {
        jdbc
            .sql(
                """
                UPDATE telegram_dialog SET step = :step, initial_setup = :initial, opening_balance =
                :opening, started_on = :date,
                report_time = :time, cash_reserve = :reserve WHERE owner_id = :owner
                """.trimIndent(),
            ).param("step", dialog.step.name)
            .param("initial", dialog.initialSetup)
            .param("opening", dialog.openingBalance?.amount)
            .param("date", dialog.startedOn)
            .param("time", dialog.reportTime)
            .param("reserve", dialog.cashReserve.amount)
            .param("owner", ownerId)
            .update()
    }

    override fun saveSettings(
        ownerId: Long,
        settings: BudgetSettings,
    ) {
        val current = settings(ownerId)
        if (current == null) {
            val household = Household(UUID.randomUUID(), reportTime = settings.reportTime)
            accounting.openBudget(
                BudgetSetup(
                    household,
                    Member(UUID.randomUUID(), household.id, ownerId),
                    Account(
                        UUID.randomUUID(),
                        household.id,
                        settings.openingBalance,
                        settings.startedOn.atStartOfDay(ACCOUNTING_ZONE).toInstant(),
                    ),
                    emptyList(),
                ),
            )
        } else if (current.settings.openingBalance != settings.openingBalance ||
            current.settings.startedOn != settings.startedOn
        ) {
            check(!current.hasEntries) { "Opening snapshot is immutable after ledger entries" }
            jdbc
                .sql(
                    """
                    UPDATE account SET opening_balance = ?, accounting_started_at = ?
                    WHERE household_id = (SELECT household_id FROM member WHERE telegram_user_id = ?)
                    """.trimIndent(),
                ).params(
                    settings.openingBalance.amount,
                    settings.startedOn.atStartOfDay(ACCOUNTING_ZONE).toOffsetDateTime(),
                    ownerId,
                ).update()
        }
        jdbc
            .sql(
                """
                UPDATE household SET report_time = ?, cash_reserve = ?, settings_version = settings_version + 1
                WHERE id = (SELECT household_id FROM member WHERE telegram_user_id = ?)
                """.trimIndent(),
            ).params(settings.reportTime, settings.cashReserve.amount, ownerId)
            .update()
    }
}
