package com.firkatdavletov.financeconsultante.ledger

import com.firkatdavletov.financeconsultante.ledger.application.RecordEntry
import com.firkatdavletov.financeconsultante.ledger.domain.Account
import com.firkatdavletov.financeconsultante.ledger.domain.BudgetSetup
import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.ledger.domain.Household
import com.firkatdavletov.financeconsultante.ledger.domain.Member
import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import java.time.Instant
import java.util.UUID

internal object AccountingFixtures {
    val start: Instant = Instant.parse("2026-02-28T19:00:00Z")
    val cutoff: Instant = Instant.parse("2026-03-02T00:00:00Z")

    fun budget(opening: String = "0"): BudgetSetup {
        val household = Household(UUID.randomUUID())
        return BudgetSetup(
            household,
            Member(UUID.randomUUID(), household.id, UUID.randomUUID().mostSignificantBits ushr 1),
            Account(UUID.randomUUID(), household.id, Money.of(opening), start),
            listOf(
                Category(UUID.randomUUID(), household.id, "Тестовый доход", CategoryKind.INCOME),
                Category(UUID.randomUUID(), household.id, "Тестовый расход", CategoryKind.EXPENSE),
            ),
        )
    }

    fun command(
        setup: BudgetSetup,
        amount: String = "100",
        direction: Direction = Direction.EXPENSE,
        currency: Currency = Currency.KZT,
    ): RecordEntry =
        RecordEntry(
            setup.household.id,
            setup.owner.id,
            setup.categories.single { it.kind == direction.categoryKind }.id,
            direction,
            Money.of(amount, currency),
            start,
            UUID.randomUUID().toString(),
        )
}
