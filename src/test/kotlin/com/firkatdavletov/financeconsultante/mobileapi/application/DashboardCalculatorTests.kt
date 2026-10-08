package com.firkatdavletov.financeconsultante.mobileapi.application

import com.firkatdavletov.financeconsultante.analytics.domain.BudgetForecast
import com.firkatdavletov.financeconsultante.analytics.domain.CategoryAnalytics
import com.firkatdavletov.financeconsultante.analytics.domain.ForecastDay
import com.firkatdavletov.financeconsultante.analytics.domain.ForecastScenario
import com.firkatdavletov.financeconsultante.analytics.domain.PeriodAnalytics
import com.firkatdavletov.financeconsultante.analytics.domain.SpendingEstimate
import com.firkatdavletov.financeconsultante.analytics.domain.SpendingEstimateSource
import com.firkatdavletov.financeconsultante.budget.domain.BudgetCalculationInput
import com.firkatdavletov.financeconsultante.budget.domain.BudgetCalculator
import com.firkatdavletov.financeconsultante.budget.domain.BudgetState
import com.firkatdavletov.financeconsultante.recurring.domain.RecurringOccurrence
import com.firkatdavletov.financeconsultante.recurring.domain.RecurringOccurrenceView
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class DashboardCalculatorTests {
    @Test
    @Suppress("LongMethod") // One control example verifies the relationships between every dashboard indicator.
    fun `calculates free money cash flow budget pace forecast and expense shares`() {
        val budget =
            BudgetCalculator.calculate(
                BudgetCalculationInput(
                    money("620000"),
                    money("200000"),
                    money("100000"),
                    money("40000"),
                    DATE,
                ),
            )
        val categories =
            listOf(
                category("Жильё", "250000", "10000"),
                category("Продукты", "120000", "5000"),
                category("Транспорт", "60000", "0"),
                category("Другое", "120000", "5000"),
            )
        val actuals = PeriodAnalytics(money("850000"), money("550000"), money("20000"), categories)

        val dashboard =
            DashboardCalculator.calculate(
                DashboardCalculationInput(
                    AS_OF,
                    DATE,
                    budget,
                    actuals,
                    money("250000"),
                    forecast(SpendingEstimateSource.HISTORICAL_AVERAGE),
                    listOf(upcomingPayment()),
                ),
            )

        assertThat(dashboard.availableMoney).isEqualTo(money("620000"))
        assertThat(dashboard.freeMoney.state).isEqualTo(BudgetState.AVAILABLE)
        assertThat(dashboard.freeMoney.reservedTotal).isEqualTo(money("340000"))
        assertThat(dashboard.freeMoney.amount).isEqualTo(money("280000"))
        assertThat(dashboard.freeMoney.safeDailyLimit).isEqualTo(money("11666"))
        assertThat(dashboard.cashFlow.net).isEqualTo(money("320000"))
        assertThat(dashboard.budgetExecution.basis).isEqualTo(BudgetPlanBasis.FORECAST)
        assertThat(dashboard.budgetExecution.total.spent).isEqualTo(money("530000"))
        assertThat(dashboard.budgetExecution.total.plannedAmount).isEqualTo(money("650000"))
        assertThat(
            dashboard.budgetExecution.total.usedPercent
                ?.toPlainString(),
        ).isEqualTo("81.5")
        assertThat(dashboard.budgetExecution.total.paceStatus).isEqualTo(BudgetPaceStatus.AHEAD_OF_PLAN)
        assertThat(dashboard.budgetExecution.variable.plannedAmount).isEqualTo(money("315000"))
        assertThat(dashboard.forecast.base.expectedExpenses).isEqualTo(money("120000"))
        assertThat(dashboard.forecast.base.endingBalance).isEqualTo(money("500000"))
        assertThat(dashboard.expenseStructure.shareBasisAmount).isEqualTo(money("530000"))
        assertThat(
            dashboard.expenseStructure.categories
                .first()
                .name,
        ).isEqualTo("Жильё")
        assertThat(
            dashboard.expenseStructure.categories
                .first()
                .sharePercent
                .toPlainString(),
        ).isEqualTo("45.3")
        assertThat(dashboard.upcomingPayments.single().remainingAmount).isEqualTo(money("55000"))
    }

    @Test
    fun `marks pace as incomplete when variable spending estimate is unavailable`() {
        val budget =
            BudgetCalculator.calculate(
                BudgetCalculationInput(money("100"), money("0"), money("0"), money("0"), DATE),
            )
        val dashboard =
            DashboardCalculator.calculate(
                DashboardCalculationInput(
                    AS_OF,
                    DATE,
                    budget,
                    PeriodAnalytics.empty(),
                    money("0"),
                    forecast(SpendingEstimateSource.UNAVAILABLE),
                    emptyList(),
                ),
            )

        assertThat(dashboard.budgetExecution.total.paceStatus)
            .isEqualTo(BudgetPaceStatus.INSUFFICIENT_DATA)
        assertThat(dashboard.budgetExecution.variable.paceStatus)
            .isEqualTo(BudgetPaceStatus.INSUFFICIENT_DATA)
    }

    private fun forecast(source: SpendingEstimateSource): BudgetForecast {
        val day =
            ForecastDay(
                DATE,
                money("0"),
                money("55000"),
                money("65000"),
                money("0"),
                money("500000"),
                money("0"),
                money("40000"),
                emptyMap(),
            )
        val scenario = ForecastScenario(listOf(day), money("500000"), money("500000"), null, emptyList())
        return BudgetForecast(
            DATE,
            DATE.withDayOfMonth(DATE.lengthOfMonth()),
            money("620000"),
            money("100000"),
            SpendingEstimate(source, 30, money("100000"), money("65000"), money("78000")),
            scenario,
            scenario,
            cautiousIncomeDelayDays = 3,
            cautiousSpendingPercent = 20,
        )
    }

    private fun category(
        name: String,
        expenses: String,
        refunds: String,
    ) = CategoryAnalytics(UUID.randomUUID(), name, money(expenses), money(refunds))

    private fun upcomingPayment(): RecurringOccurrenceView =
        RecurringOccurrenceView(
            RecurringOccurrence(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                money("55000"),
                DATE.plusDays(4),
                createdAt = AS_OF,
            ),
            "Кредит",
            money("0"),
        )

    private fun money(amount: String) = Money.of(amount)

    companion object {
        private val AS_OF = Instant.parse("2026-10-08T04:00:00Z")
        private val DATE = LocalDate.parse("2026-10-08")
    }
}
