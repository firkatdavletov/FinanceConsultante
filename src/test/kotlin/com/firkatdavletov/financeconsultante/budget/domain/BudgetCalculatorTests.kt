package com.firkatdavletov.financeconsultante.budget.domain

import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class BudgetCalculatorTests {
    @Test
    fun `control example includes goal reserve and floors the daily limit`() {
        val result =
            BudgetCalculator.calculate(
                BudgetCalculationInput(
                    Money.of("300000"),
                    Money.of("100000"),
                    Money.of("50000"),
                    Money.of("30000"),
                    LocalDate.parse("2026-09-21"),
                ),
            )

        assertThat(result.state).isEqualTo(BudgetState.AVAILABLE)
        assertThat(result.freeAmount).isEqualTo(Money.of("120000"))
        assertThat(result.daysRemaining).isEqualTo(10)
        assertThat(result.dailyLimit).isEqualTo(Money.of("12000"))
    }

    @Test
    fun `MVP ignores future goals and uses fifteen thousand limit`() {
        val result =
            BudgetCalculator.calculate(
                BudgetCalculationInput(
                    Money.of("300000"),
                    Money.of("100000"),
                    Money.of("50000"),
                    Money.of("0"),
                    LocalDate.parse("2026-09-21"),
                ),
            )

        assertThat(result.freeAmount).isEqualTo(Money.of("150000"))
        assertThat(result.dailyLimit).isEqualTo(Money.of("15000"))
    }

    @Test
    fun `deficit has zero limit and last day has one remaining day`() {
        val result =
            BudgetCalculator.calculate(
                BudgetCalculationInput(
                    Money.of("99"),
                    Money.of("100"),
                    Money.of("0"),
                    Money.of("0"),
                    LocalDate.parse("2028-02-29"),
                ),
            )

        assertThat(result.state).isEqualTo(BudgetState.DEFICIT)
        assertThat(result.deficit).isEqualTo(Money.of("1"))
        assertThat(result.daysRemaining).isEqualTo(1)
        assertThat(result.dailyLimit).isEqualTo(Money.of("0"))
    }

    @Test
    fun `unknown opening balance is explicitly incomplete`() {
        val result =
            BudgetCalculator.calculate(
                BudgetCalculationInput(
                    null,
                    Money.of("0"),
                    Money.of("0"),
                    Money.of("0"),
                    LocalDate.parse("2026-09-06"),
                ),
            )

        assertThat(result.state).isEqualTo(BudgetState.INCOMPLETE)
        assertThat(result.freeAmount).isNull()
        assertThat(result.dailyLimit).isNull()
    }
}
