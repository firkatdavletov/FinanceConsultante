package com.firkatdavletov.financeconsultante.ledger.application

import com.firkatdavletov.financeconsultante.ledger.domain.Account
import com.firkatdavletov.financeconsultante.ledger.domain.BudgetSetup
import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.ledger.domain.DailyExchangeRate
import com.firkatdavletov.financeconsultante.ledger.domain.LedgerEntry
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface BudgetRepository {
    fun create(setup: BudgetSetup)

    fun findOwnedAccount(
        householdId: UUID,
        memberId: UUID,
    ): Account?

    fun findCategory(
        householdId: UUID,
        categoryId: UUID,
    ): Category?
}

interface LedgerRepository {
    fun findBySource(
        householdId: UUID,
        sourceKey: String,
    ): LedgerEntry?

    /** Inserts the entry and its initial audit revision in the caller's transaction. */
    fun insert(entry: LedgerEntry): Boolean

    fun findBefore(
        householdId: UUID,
        untilExclusive: Instant,
    ): List<LedgerEntry>
}

interface ExchangeRateRepository {
    fun find(date: LocalDate): DailyExchangeRate?

    /** Idempotent for the same daily rate; conflicting snapshots must be rejected. */
    fun fix(rate: DailyExchangeRate): DailyExchangeRate
}
