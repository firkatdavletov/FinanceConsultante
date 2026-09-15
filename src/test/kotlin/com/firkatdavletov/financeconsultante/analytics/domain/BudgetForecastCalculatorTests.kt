package com.firkatdavletov.financeconsultante.analytics.domain

import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

@Suppress("MagicNumber")
class BudgetForecastCalculatorTests {
    @Test
    fun `obligation before income exposes cash gap although ending balance is positive`() {
        val start = LocalDate.parse("2026-01-30")
        val forecast =
            calculate(
                start = start,
                balance = "100",
                flows =
                    listOf(
                        flow(start.plusDays(1), ForecastCashFlowType.OBLIGATION, "200"),
                        flow(start.plusDays(3), ForecastCashFlowType.EXPECTED_INCOME, "400"),
                    ),
            )

        assertThat(forecast.base.firstDeficitDate).isEqualTo(start.plusDays(1))
        assertThat(forecast.base.minimumDailyBalance).isEqualTo(Money.of("-100"))
        assertThat(forecast.base.endingBalance).isEqualTo(Money.of("300"))
        assertThat(
            forecast.base.days
                .single { it.date == start.plusDays(3) }
                .expectedIncome,
        ).isEqualTo(Money.of("400"))
        assertThat(forecast.base.days.filter { it.expectedIncome.amount.signum() > 0 }).hasSize(1)
    }

    @Test
    fun `cautious scenario delays irregular incomes and increases estimated spending`() {
        val start = LocalDate.parse("2026-09-01")
        val forecast =
            BudgetForecastCalculator.calculate(
                BudgetForecastInput(
                    start,
                    Money.of("1000"),
                    Money.of("0"),
                    historyDays = 30,
                    historicalVariableSpending = Money.of("300"),
                    cashFlows =
                        listOf(
                            flow(start.plusDays(2), ForecastCashFlowType.EXPECTED_INCOME, "100"),
                            flow(start.plusDays(8), ForecastCashFlowType.EXPECTED_INCOME, "50"),
                        ),
                ),
            )

        assertThat(forecast.spending.source).isEqualTo(SpendingEstimateSource.HISTORICAL_AVERAGE)
        assertThat(forecast.spending.baseDaily).isEqualTo(Money.of("10"))
        assertThat(forecast.spending.cautiousDaily).isEqualTo(Money.of("12"))
        assertThat(
            forecast.cautious.days
                .single { it.date == start.plusDays(2) }
                .expectedIncome,
        ).isEqualTo(Money.of("0"))
        assertThat(
            forecast.cautious.days
                .single { it.date == start.plusDays(5) }
                .expectedIncome,
        ).isEqualTo(Money.of("100"))
        assertThat(
            forecast.cautious.days
                .single { it.date == start.plusDays(11) }
                .expectedIncome,
        ).isEqualTo(Money.of("50"))
    }

    @Test
    fun `short history omits variable estimate until manual daily limit is supplied`() {
        val start = LocalDate.parse("2026-02-20")
        val preliminary =
            BudgetForecastCalculator.calculate(
                BudgetForecastInput(
                    start,
                    Money.of("1000"),
                    Money.of("0"),
                    historyDays = 13,
                    historicalVariableSpending = Money.of("1300"),
                ),
            )
        val manual =
            BudgetForecastCalculator.calculate(
                BudgetForecastInput(
                    start,
                    Money.of("1000"),
                    Money.of("0"),
                    historyDays = 0,
                    historicalVariableSpending = Money.of("0"),
                    manualDailySpending = Money.of("20"),
                ),
            )

        assertThat(preliminary.spending.source).isEqualTo(SpendingEstimateSource.UNAVAILABLE)
        assertThat(preliminary.base.endingBalance).isEqualTo(Money.of("1000"))
        assertThat(manual.spending.source).isEqualTo(SpendingEstimateSource.MANUAL)
        assertThat(manual.base.endingBalance).isEqualTo(Money.of("400"))
        assertThat(manual.endDate).isEqualTo(LocalDate.parse("2026-03-21"))
    }

    @Test
    fun `goal allocation and contribution plans reserve funds without cash outflow`() {
        val start = LocalDate.parse("2026-09-01")
        val goal =
            ForecastGoal(
                UUID.randomUUID(),
                "Отпуск",
                Money.of("300"),
                Money.of("100"),
                start.plusDays(10),
                1,
                listOf(ForecastContribution(start.plusDays(2), Money.of("200"))),
            )
        val forecast =
            BudgetForecastCalculator.calculate(
                BudgetForecastInput(
                    start,
                    Money.of("500"),
                    Money.of("50"),
                    historyDays = 30,
                    historicalVariableSpending = Money.of("0"),
                    goals = listOf(goal),
                ),
            )

        assertThat(forecast.base.endingBalance).isEqualTo(Money.of("500"))
        assertThat(
            forecast.base.goals
                .single()
                .projectedAllocation,
        ).isEqualTo(Money.of("300"))
        assertThat(
            forecast.base.goals
                .single()
                .reachability,
        ).isEqualTo(GoalReachability.REACHABLE)
    }

    @Test
    fun `large one-off expense remains in the thirty completed day average including zero days`() {
        val forecast =
            BudgetForecastCalculator.calculate(
                BudgetForecastInput(
                    LocalDate.parse("2026-09-01"),
                    Money.of("10000"),
                    Money.of("0"),
                    historyDays = 30,
                    historicalVariableSpending = Money.of("3000"),
                ),
            )

        assertThat(forecast.spending.baseDaily).isEqualTo(Money.of("100"))
        assertThat(forecast.base.endingBalance).isEqualTo(Money.of("7000"))
    }

    private fun calculate(
        start: LocalDate,
        balance: String,
        flows: List<ForecastCashFlow>,
    ): BudgetForecast =
        BudgetForecastCalculator.calculate(
            BudgetForecastInput(
                start,
                Money.of(balance),
                Money.of("0"),
                historyDays = 30,
                historicalVariableSpending = Money.of("0"),
                cashFlows = flows,
            ),
        )

    private fun flow(
        date: LocalDate,
        type: ForecastCashFlowType,
        amount: String,
    ): ForecastCashFlow = ForecastCashFlow(date, type, Money.of(amount))
}
