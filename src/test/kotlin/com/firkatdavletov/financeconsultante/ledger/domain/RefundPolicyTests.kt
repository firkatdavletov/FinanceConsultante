package com.firkatdavletov.financeconsultante.ledger.domain

import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class RefundPolicyTests {
    private val date = Instant.parse("2026-09-02T00:00:00Z")
    private val expense =
        LedgerEntry(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            Direction.EXPENSE,
            Money.of("1300"),
            Money.of("1300"),
            date,
            date,
            date,
            "synthetic:expense",
        )
    private val refund =
        expense.copy(
            id = UUID.randomUUID(),
            direction = Direction.EXPENSE_REFUND,
            originalAmount = Money.of("300"),
            amountKzt = Money.of("300"),
            refundOf = expense.id,
        )

    @Test
    fun `partial refunds reduce net expenses without increasing income`() {
        assertThat(RefundPolicy.rejection(expense, emptyList(), refund)).isNull()
        val account = Account(expense.accountId, expense.householdId, Money.of("0"), date)
        val balance = Balance.calculate(account, listOf(expense, refund), date.plusSeconds(1))
        assertThat(balance.income).isEqualTo(Money.of("0"))
        assertThat(balance.netExpenses).isEqualTo(Money.of("1000"))
        assertThat(balance.amount).isEqualTo(Money.of("-1000"))
    }

    @Test
    fun `refund correction excludes itself but counts other posted refunds`() {
        val other = refund.copy(id = UUID.randomUUID())
        val candidate = refund.copy(originalAmount = Money.of("1000"), amountKzt = Money.of("1000"))
        assertThat(RefundPolicy.rejection(expense, listOf(refund, other), candidate)).isNull()
        val over = candidate.copy(originalAmount = Money.of("1000.01"), amountKzt = Money.of("1000.01"))
        assertThat(RefundPolicy.rejection(expense, listOf(refund, other), over)).contains("превышает")
        assertThat(RefundPolicy.rejection(expense, listOf(other.copy(status = EntryStatus.VOIDED)), over)).isNull()
    }

    @Test
    fun `voided expense wrong category and earlier refund are rejected`() {
        assertThat(RefundPolicy.rejection(expense.copy(status = EntryStatus.VOIDED), emptyList(), refund)).isNotNull()
        assertThat(
            RefundPolicy.rejection(expense, emptyList(), refund.copy(categoryId = UUID.randomUUID())),
        ).isNotNull()
        assertThat(
            RefundPolicy.rejection(
                expense.copy(occurredAt = date.plusSeconds(1), modifiedAt = date.plusSeconds(1)),
                emptyList(),
                refund,
            ),
        ).contains("раньше")
    }

    @Test
    fun `different RUB rates use original amount for refund ceiling`() {
        val first =
            DailyExchangeRate(java.time.LocalDate.parse("2026-09-02"), java.math.BigDecimal("5"), "SYNTHETIC", date)
        val later = first.copy(date = first.date.plusDays(1), rubKzt = java.math.BigDecimal("6"))
        val rubExpense =
            expense.copy(
                originalAmount = Money.of("10", Currency.RUB),
                amountKzt = Money.of("50"),
                exchangeRate = first,
            )
        val rubRefund =
            refund.copy(
                originalAmount = Money.of("10", Currency.RUB),
                amountKzt = Money.of("60"),
                exchangeRate = later,
                occurredAt = date.plusSeconds(86400),
                modifiedAt = date.plusSeconds(86400),
            )
        assertThat(RefundPolicy.rejection(rubExpense, emptyList(), rubRefund)).isNull()
        assertThat(RefundPolicy.rejection(rubExpense, emptyList(), refund)).contains("валюте")
    }
}
