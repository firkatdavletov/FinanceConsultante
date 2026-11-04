package com.firkatdavletov.financeconsultante.budget.application

import com.firkatdavletov.financeconsultante.access.domain.MemberRole
import com.firkatdavletov.financeconsultante.budget.domain.DailyBudgetSnapshot
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

data class BudgetSettingsView(
    val protectedReserve: Money,
    val version: Long,
)

data class DailySpendingTotals(
    val yesterday: Money,
    val lastThirtyDays: Money,
) {
    init {
        require(yesterday.currency == Currency.KZT && lastThirtyDays.currency == Currency.KZT)
    }
}

interface BudgetReportRepository {
    fun settings(
        owner: OwnedLedger,
        lock: Boolean = false,
    ): BudgetSettingsView

    fun ledgerRevision(householdId: UUID): Long

    fun snapshot(
        householdId: UUID,
        date: LocalDate,
    ): DailyBudgetSnapshot?

    fun save(snapshot: DailyBudgetSnapshot)

    fun ordinarySpendingAfter(snapshot: DailyBudgetSnapshot): Money

    fun ordinarySpendingTotals(
        householdId: UUID,
        date: LocalDate,
    ): DailySpendingTotals

    fun hasInvalidatingLedgerChanges(snapshot: DailyBudgetSnapshot): Boolean
}
