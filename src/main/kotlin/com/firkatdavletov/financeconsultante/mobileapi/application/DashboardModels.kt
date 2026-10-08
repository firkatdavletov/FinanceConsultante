package com.firkatdavletov.financeconsultante.mobileapi.application

import com.firkatdavletov.financeconsultante.analytics.domain.BudgetForecast
import com.firkatdavletov.financeconsultante.analytics.domain.ForecastScenario
import com.firkatdavletov.financeconsultante.analytics.domain.PeriodAnalytics
import com.firkatdavletov.financeconsultante.analytics.domain.SpendingEstimateSource
import com.firkatdavletov.financeconsultante.budget.domain.BudgetEstimate
import com.firkatdavletov.financeconsultante.budget.domain.BudgetState
import com.firkatdavletov.financeconsultante.recurring.domain.RecurringOccurrenceView
import com.firkatdavletov.financeconsultante.shared.domain.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class BudgetPlanBasis {
    FORECAST,
}

enum class BudgetPaceStatus {
    AHEAD_OF_PLAN,
    ON_PLAN,
    BELOW_PLAN,
    INSUFFICIENT_DATA,
}

data class DashboardPeriod(
    val startInclusive: LocalDate,
    val endInclusive: LocalDate,
    val elapsedPercent: BigDecimal,
)

data class DashboardFreeMoney(
    val amount: Money,
    val state: BudgetState,
    val remainingObligations: Money,
    val protectedReserve: Money,
    val goalReserve: Money,
    val reservedTotal: Money,
    val daysRemaining: Int,
    val safeDailyLimit: Money,
)

data class DashboardMonthActuals(
    val income: Money,
    val expenses: Money,
    val refunds: Money,
    val netExpenses: Money,
)

data class DashboardCashFlow(
    val income: Money,
    val expenses: Money,
    val refunds: Money,
    val otherOutflows: Money,
    val net: Money,
)

data class DashboardBudgetProgress(
    val spent: Money,
    val plannedAmount: Money,
    val usedPercent: BigDecimal?,
    val paceStatus: BudgetPaceStatus,
)

data class DashboardBudgetExecution(
    val basis: BudgetPlanBasis,
    val total: DashboardBudgetProgress,
    val variable: DashboardBudgetProgress,
)

data class DashboardForecastScenario(
    val expectedIncome: Money,
    val expectedObligations: Money,
    val expectedVariableExpenses: Money,
    val expectedExpenses: Money,
    val endingBalance: Money,
)

data class DashboardForecast(
    val throughInclusive: LocalDate,
    val estimateSource: SpendingEstimateSource,
    val historyDays: Int,
    val baseDailySpending: Money,
    val cautiousDailySpending: Money,
    val base: DashboardForecastScenario,
    val cautious: DashboardForecastScenario,
)

data class DashboardExpenseCategory(
    val categoryId: UUID,
    val name: String,
    val amount: Money,
    val sharePercent: BigDecimal,
)

data class DashboardExpenseStructure(
    val shareBasisAmount: Money,
    val categories: List<DashboardExpenseCategory>,
)

data class DashboardUpcomingPayment(
    val occurrenceId: UUID,
    val categoryId: UUID,
    val categoryName: String,
    val dueOn: LocalDate,
    val remainingAmount: Money,
    val overdue: Boolean,
)

data class DashboardSummary(
    val asOf: Instant,
    val date: LocalDate,
    val period: DashboardPeriod,
    val availableMoney: Money,
    val freeMoney: DashboardFreeMoney,
    val monthActuals: DashboardMonthActuals,
    val cashFlow: DashboardCashFlow,
    val budgetExecution: DashboardBudgetExecution,
    val forecast: DashboardForecast,
    val expenseStructure: DashboardExpenseStructure,
    val upcomingPayments: List<DashboardUpcomingPayment>,
)

data class DashboardCalculationInput(
    val asOf: Instant,
    val date: LocalDate,
    val budget: BudgetEstimate,
    val actuals: PeriodAnalytics,
    val actualVariableSpending: Money,
    val forecast: BudgetForecast,
    val upcomingPayments: List<RecurringOccurrenceView>,
)

@Suppress("TooManyFunctions") // Small named calculations keep every dashboard financial rule independently testable.
object DashboardCalculator {
    fun calculate(input: DashboardCalculationInput): DashboardSummary {
        val balance = checkNotNull(input.budget.balance)
        val elapsed = percent(BigDecimal(input.date.dayOfMonth), BigDecimal(input.date.lengthOfMonth()))
        val baseForecast = scenario(input.forecast.base)
        val cautiousForecast = scenario(input.forecast.cautious)

        return DashboardSummary(
            input.asOf,
            input.date,
            DashboardPeriod(
                input.date.withDayOfMonth(1),
                input.date.withDayOfMonth(input.date.lengthOfMonth()),
                elapsed,
            ),
            balance,
            freeMoney(input.budget),
            monthActuals(input.actuals),
            cashFlow(input.actuals),
            execution(input, baseForecast),
            forecast(input.forecast, baseForecast, cautiousForecast),
            expenseStructure(input.actuals),
            upcomingPayments(input),
        )
    }

    private fun freeMoney(budget: BudgetEstimate): DashboardFreeMoney =
        DashboardFreeMoney(
            checkNotNull(budget.freeAmount),
            budget.state,
            budget.remainingObligations,
            budget.protectedReserve,
            budget.goalReserve,
            budget.remainingObligations + budget.protectedReserve + budget.goalReserve,
            budget.daysRemaining,
            checkNotNull(budget.dailyLimit),
        )

    private fun monthActuals(actuals: PeriodAnalytics): DashboardMonthActuals =
        DashboardMonthActuals(actuals.income, actuals.expenses, actuals.refunds, actuals.netExpenses)

    private fun execution(
        input: DashboardCalculationInput,
        baseForecast: DashboardForecastScenario,
    ): DashboardBudgetExecution {
        val actualSpent = nonNegative(input.actuals.netExpenses)
        val actualVariable = nonNegative(input.actualVariableSpending)
        val complete = input.forecast.spending.source != SpendingEstimateSource.UNAVAILABLE
        return DashboardBudgetExecution(
            BudgetPlanBasis.FORECAST,
            progress(actualSpent, actualSpent + baseForecast.expectedExpenses, input.date, complete),
            progress(
                actualVariable,
                actualVariable + baseForecast.expectedVariableExpenses,
                input.date,
                complete,
            ),
        )
    }

    private fun forecast(
        value: BudgetForecast,
        base: DashboardForecastScenario,
        cautious: DashboardForecastScenario,
    ): DashboardForecast =
        DashboardForecast(
            value.endDate,
            value.spending.source,
            value.spending.historyDays,
            value.spending.baseDaily,
            value.spending.cautiousDaily,
            base,
            cautious,
        )

    private fun cashFlow(actuals: PeriodAnalytics): DashboardCashFlow {
        val otherOutflows = Money.of(BigDecimal.ZERO)
        val net = actuals.income - actuals.expenses + actuals.refunds - otherOutflows
        return DashboardCashFlow(actuals.income, actuals.expenses, actuals.refunds, otherOutflows, net)
    }

    private fun scenario(value: ForecastScenario): DashboardForecastScenario {
        val income = sum(value.days.map { it.expectedIncome })
        val obligations = sum(value.days.map { it.obligations })
        val variable = sum(value.days.map { it.variableSpending })
        return DashboardForecastScenario(
            income,
            obligations,
            variable,
            obligations + variable,
            value.endingBalance,
        )
    }

    private fun progress(
        spent: Money,
        planned: Money,
        date: LocalDate,
        complete: Boolean,
    ): DashboardBudgetProgress =
        DashboardBudgetProgress(
            spent,
            planned,
            planned.amount.takeIf { it.signum() > 0 }?.let { percent(spent.amount, it) },
            pace(spent.amount, planned.amount, date, complete),
        )

    private fun pace(
        spent: BigDecimal,
        planned: BigDecimal,
        date: LocalDate,
        complete: Boolean,
    ): BudgetPaceStatus =
        when {
            !complete -> {
                BudgetPaceStatus.INSUFFICIENT_DATA
            }

            planned.signum() == 0 -> {
                BudgetPaceStatus.ON_PLAN
            }

            else -> {
                val comparison =
                    spent
                        .multiply(BigDecimal(date.lengthOfMonth()))
                        .compareTo(planned.multiply(BigDecimal(date.dayOfMonth)))
                when {
                    comparison > 0 -> BudgetPaceStatus.AHEAD_OF_PLAN
                    comparison < 0 -> BudgetPaceStatus.BELOW_PLAN
                    else -> BudgetPaceStatus.ON_PLAN
                }
            }
        }

    private fun expenseStructure(actuals: PeriodAnalytics): DashboardExpenseStructure {
        val categories =
            actuals.categories
                .map { it.categoryId to (it.name to it.netExpenses) }
                .filter { (_, value) -> value.second.amount.signum() > 0 }
        val shareBasis =
            Money.of(categories.fold(BigDecimal.ZERO) { sum, category -> sum + category.second.second.amount })
        val items =
            categories
                .sortedWith(
                    compareByDescending<Pair<UUID, Pair<String, Money>>> { it.second.second.amount }
                        .thenBy { it.second.first }
                        .thenBy { it.first },
                ).take(TOP_CATEGORY_LIMIT)
                .map { (categoryId, value) ->
                    DashboardExpenseCategory(
                        categoryId,
                        value.first,
                        value.second,
                        percent(value.second.amount, shareBasis.amount),
                    )
                }
        return DashboardExpenseStructure(shareBasis, items)
    }

    private fun upcomingPayments(input: DashboardCalculationInput): List<DashboardUpcomingPayment> =
        input.upcomingPayments.take(UPCOMING_PAYMENT_LIMIT).map { payment ->
            DashboardUpcomingPayment(
                payment.occurrence.id,
                payment.occurrence.categoryId,
                payment.categoryName,
                payment.occurrence.dueOn,
                payment.remainingAmount,
                payment.overdue(input.date),
            )
        }

    private fun nonNegative(value: Money): Money = Money.of(value.amount.max(BigDecimal.ZERO), value.currency)

    private fun sum(values: List<Money>): Money =
        Money.of(values.fold(BigDecimal.ZERO) { total, value -> total + value.amount })

    private fun percent(
        numerator: BigDecimal,
        denominator: BigDecimal,
    ): BigDecimal =
        if (denominator.signum() == 0) {
            BigDecimal.ZERO.setScale(PERCENT_SCALE)
        } else {
            numerator.multiply(HUNDRED).divide(denominator, PERCENT_SCALE, RoundingMode.HALF_UP)
        }

    private const val TOP_CATEGORY_LIMIT = 5
    private const val UPCOMING_PAYMENT_LIMIT = 5
    private const val PERCENT_SCALE = 1
    private val HUNDRED = BigDecimal("100")
}
