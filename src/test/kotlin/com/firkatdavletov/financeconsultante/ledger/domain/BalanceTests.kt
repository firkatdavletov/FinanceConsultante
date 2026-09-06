package com.firkatdavletov.financeconsultante.ledger.domain

import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class BalanceTests {
    private val start = Instant.parse("2026-02-28T19:00:00Z")
    private val end = Instant.parse("2026-03-01T19:00:00Z")
    private val account = Account(UUID.randomUUID(), UUID.randomUUID(), Money.of("0"), start)

    @Test
    fun `reference ledger produces correct balance and expenses`() {
        val entries =
            listOf(
                entry(Direction.INCOME, "1300000"),
                entry(Direction.EXPENSE, "15000"),
                entry(Direction.EXPENSE, "700"),
                entry(Direction.EXPENSE, "1300"),
            )
        val balance = Balance.calculate(account, entries, end)
        assertThat(balance.amount).isEqualTo(Money.of("1283000"))
        assertThat(balance.income).isEqualTo(Money.of("1300000"))
        assertThat(balance.expenses).isEqualTo(Money.of("17000"))
    }

    @Test
    fun `opening balance and refunds do not become income and debt remains negative`() {
        val entries = listOf(entry(Direction.EXPENSE, "1300"), entry(Direction.EXPENSE_REFUND, "300"))
        val balance = Balance.calculate(account.copy(openingBalance = Money.of("100")), entries, end)
        assertThat(balance.amount).isEqualTo(Money.of("-900"))
        assertThat(balance.income).isEqualTo(Money.of("0"))
        assertThat(balance.netExpenses).isEqualTo(Money.of("1000"))
        assertThat(balance.refunds).isEqualTo(Money.of("300"))
    }

    @Test
    fun `voided entries and exclusive upper boundary do not affect balance`() {
        val entries =
            listOf(
                entry(Direction.INCOME, "100"),
                entry(Direction.EXPENSE, "70").copy(status = EntryStatus.VOIDED),
                entry(Direction.EXPENSE, "20").copy(occurredAt = end),
            )
        assertThat(Balance.calculate(account, entries, end).amount).isEqualTo(Money.of("100"))
        assertThat(Balance.calculate(account, entries, start).amount).isEqualTo(Money.of("0"))
    }

    @Test
    fun `entries before accounting or after creation and foreign accounts are rejected`() {
        val income = entry(Direction.INCOME, "100")
        assertThatIllegalArgumentException().isThrownBy { income.copy(occurredAt = start.minusSeconds(1)) }
        assertThatIllegalArgumentException().isThrownBy { income.copy(occurredAt = end.plusSeconds(1)) }
        assertThatIllegalArgumentException().isThrownBy { income.copy(occurredAt = end.minusNanos(1)) }
        assertThatIllegalArgumentException().isThrownBy { account.copy(accountingStartedAt = start.plusNanos(1)) }
        assertThatIllegalArgumentException().isThrownBy {
            Balance.calculate(account, listOf(income.copy(accountId = UUID.randomUUID())), end)
        }
        assertThatIllegalArgumentException().isThrownBy {
            Balance.calculate(
                account,
                emptyList(),
                start.minusSeconds(1),
            )
        }
    }

    @Test
    fun `RUB operations use local date rate and frozen KZT valuation`() {
        val rate = DailyExchangeRate(LocalDate.parse("2026-03-01"), BigDecimal("5.125"), "TEST", start)
        val rubles = Money.of("100", Currency.RUB)
        val expense = entry(Direction.EXPENSE, "512.50").copy(originalAmount = rubles, exchangeRate = rate)
        assertThat(Balance.calculate(account, listOf(expense), end).amount).isEqualTo(Money.of("-512.50"))
        assertThat(rate.convert(Money.of("0.01", Currency.RUB))).isEqualTo(Money.of("0.05"))
        assertThat(rate.convert(Money.of("1", Currency.RUB))).isEqualTo(Money.of("5.13"))
        assertThatIllegalArgumentException().isThrownBy { expense.copy(exchangeRate = null) }
        assertThatIllegalArgumentException().isThrownBy {
            expense.copy(exchangeRate = rate.copy(date = rate.date.minusDays(1)))
        }
        assertThatIllegalArgumentException().isThrownBy { expense.copy(amountKzt = Money.of("513")) }
    }

    private fun entry(
        direction: Direction,
        amount: String,
    ): LedgerEntry =
        LedgerEntry(
            id = UUID.randomUUID(),
            householdId = account.householdId,
            accountId = account.id,
            categoryId = UUID.randomUUID(),
            actorId = UUID.randomUUID(),
            direction = direction,
            originalAmount = Money.of(amount),
            amountKzt = Money.of(amount),
            accountingStartedAt = start,
            occurredAt = start,
            createdAt = end,
            sourceKey = UUID.randomUUID().toString(),
        )
}
