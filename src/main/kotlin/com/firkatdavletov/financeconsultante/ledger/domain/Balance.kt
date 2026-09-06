package com.firkatdavletov.financeconsultante.ledger.domain

import com.firkatdavletov.financeconsultante.shared.domain.Money
import java.time.Instant

data class Balance(
    val amount: Money,
    val income: Money,
    val expenses: Money,
    val refunds: Money,
) {
    val netExpenses: Money get() = expenses - refunds

    companion object {
        fun calculate(
            account: Account,
            entries: List<LedgerEntry>,
            untilExclusive: Instant,
        ): Balance {
            require(untilExclusive >= account.accountingStartedAt) { "Balance predates accounting" }
            require(entries.all { it.householdId == account.householdId && it.accountId == account.id }) {
                "Entry belongs to a different account"
            }
            val posted =
                entries.filter {
                    it.status == EntryStatus.POSTED &&
                        it.occurredAt >= account.accountingStartedAt && it.occurredAt < untilExclusive
                }

            fun total(direction: Direction): Money =
                posted
                    .filter { it.direction == direction }
                    .fold(Money.of("0")) { sum, entry -> sum + entry.amountKzt }

            val income = total(Direction.INCOME)
            val expenses = total(Direction.EXPENSE)
            val refunds = total(Direction.EXPENSE_REFUND)
            return Balance(account.openingBalance + income - expenses + refunds, income, expenses, refunds)
        }
    }
}
