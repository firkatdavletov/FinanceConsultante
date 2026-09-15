package com.firkatdavletov.financeconsultante.analytics.domain

import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

enum class ForecastCashFlowType {
    EXPECTED_INCOME,
    OBLIGATION,
}

data class ForecastCashFlow(
    val date: LocalDate,
    val type: ForecastCashFlowType,
    val amount: Money,
) {
    init {
        require(amount.currency == Currency.KZT && amount.amount.signum() > 0)
    }
}

data class ForecastContribution(
    val date: LocalDate,
    val amount: Money,
) {
    init {
        require(amount.currency == Currency.KZT && amount.amount.signum() > 0)
    }
}

data class ForecastPurchase(
    val date: LocalDate,
    val amount: Money,
    val goalId: UUID? = null,
) {
    init {
        require(amount.currency == Currency.KZT && amount.amount.signum() > 0)
    }
}

data class ForecastGoal(
    val id: UUID,
    val name: String,
    val targetAmount: Money,
    val allocatedAmount: Money,
    val deadline: LocalDate,
    val priority: Int,
    val contributions: List<ForecastContribution>,
) {
    init {
        require(name.isNotBlank())
        require(targetAmount.currency == Currency.KZT && targetAmount.amount.signum() > 0)
        require(allocatedAmount.currency == Currency.KZT && allocatedAmount.amount.signum() >= 0)
        require(allocatedAmount.amount <= targetAmount.amount)
        require(priority > 0)
    }
}

data class BudgetForecastInput(
    val startDate: LocalDate,
    val currentBalance: Money,
    val protectedReserve: Money,
    val historyDays: Int,
    val historicalVariableSpending: Money,
    val manualDailySpending: Money? = null,
    val cashFlows: List<ForecastCashFlow> = emptyList(),
    val goals: List<ForecastGoal> = emptyList(),
    val horizonDays: Int = BudgetForecastCalculator.HORIZON_DAYS,
    val purchase: ForecastPurchase? = null,
) {
    init {
        require(currentBalance.currency == Currency.KZT)
        require(protectedReserve.currency == Currency.KZT && protectedReserve.amount.signum() >= 0)
        require(historyDays in 0..BudgetForecastCalculator.HISTORY_DAYS)
        require(
            historicalVariableSpending.currency == Currency.KZT &&
                historicalVariableSpending.amount.signum() >= 0,
        )
        manualDailySpending?.let {
            require(it.currency == Currency.KZT && it.amount.signum() >= 0)
        }
        require(horizonDays > 0)
    }
}

enum class SpendingEstimateSource {
    HISTORICAL_AVERAGE,
    MANUAL,
    UNAVAILABLE,
}

data class SpendingEstimate(
    val source: SpendingEstimateSource,
    val historyDays: Int,
    val historicalTotal: Money,
    val baseDaily: Money,
    val cautiousDaily: Money,
)

data class ForecastDay(
    val date: LocalDate,
    val expectedIncome: Money,
    val obligations: Money,
    val variableSpending: Money,
    val purchase: Money,
    val closingBalance: Money,
    val goalFunding: Money,
    val goalReserve: Money,
    val goalAllocations: Map<UUID, Money>,
)

enum class GoalReachability {
    ACHIEVED,
    REACHABLE,
    AT_RISK,
    OVERDUE,
    OUTSIDE_HORIZON,
}

data class GoalForecast(
    val goal: ForecastGoal,
    val projectedAllocation: Money,
    val shortfall: Money,
    val reachability: GoalReachability,
)

data class ForecastScenario(
    val days: List<ForecastDay>,
    val endingBalance: Money,
    val minimumDailyBalance: Money,
    val firstDeficitDate: LocalDate?,
    val goals: List<GoalForecast>,
)

data class BudgetForecast(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val currentBalance: Money,
    val protectedReserve: Money,
    val spending: SpendingEstimate,
    val base: ForecastScenario,
    val cautious: ForecastScenario,
    val cautiousIncomeDelayDays: Int,
    val cautiousSpendingPercent: Int,
)

object BudgetForecastCalculator {
    const val HORIZON_DAYS = 30
    const val HISTORY_DAYS = 30
    const val MIN_HISTORY_DAYS = 14
    const val CAUTIOUS_INCOME_DELAY_DAYS = 3
    const val CAUTIOUS_SPENDING_PERCENT = 20

    fun calculate(input: BudgetForecastInput): BudgetForecast {
        val endDate = input.startDate.plusDays(input.horizonDays.toLong() - 1)
        require(input.cashFlows.all { it.date <= endDate }) { "Cash flow falls outside forecast horizon" }
        input.purchase?.let { purchase ->
            require(purchase.date in input.startDate..endDate) { "Purchase falls outside forecast horizon" }
            require(purchase.goalId == null || input.goals.any { it.id == purchase.goalId }) {
                "Purchase goal is not part of the forecast"
            }
        }
        val spending = spending(input)
        val base = scenario(input, endDate, spending.baseDaily, incomeDelayDays = 0)
        val cautious = scenario(input, endDate, spending.cautiousDaily, CAUTIOUS_INCOME_DELAY_DAYS)
        return BudgetForecast(
            input.startDate,
            endDate,
            input.currentBalance,
            input.protectedReserve,
            spending,
            base,
            cautious,
            CAUTIOUS_INCOME_DELAY_DAYS,
            CAUTIOUS_SPENDING_PERCENT,
        )
    }

    private fun spending(input: BudgetForecastInput): SpendingEstimate {
        val source: SpendingEstimateSource
        val baseAmount =
            when {
                input.manualDailySpending != null -> {
                    source = SpendingEstimateSource.MANUAL
                    input.manualDailySpending.amount
                }

                input.historyDays >= MIN_HISTORY_DAYS -> {
                    source = SpendingEstimateSource.HISTORICAL_AVERAGE
                    input.historicalVariableSpending.amount.divide(
                        BigDecimal(input.historyDays),
                        MONEY_SCALE,
                        RoundingMode.HALF_UP,
                    )
                }

                else -> {
                    source = SpendingEstimateSource.UNAVAILABLE
                    BigDecimal.ZERO
                }
            }
        val base = Money.of(baseAmount.max(BigDecimal.ZERO))
        val cautious =
            Money.of(
                base.amount
                    .multiply(CAUTIOUS_SPENDING_MULTIPLIER)
                    .setScale(MONEY_SCALE, RoundingMode.CEILING),
            )
        return SpendingEstimate(source, input.historyDays, input.historicalVariableSpending, base, cautious)
    }

    @Suppress("LongMethod") // The loop intentionally keeps cash and non-cash reservations in one daily simulation.
    private fun scenario(
        input: BudgetForecastInput,
        endDate: LocalDate,
        dailySpending: Money,
        incomeDelayDays: Int,
    ): ForecastScenario {
        val flows =
            input.cashFlows
                .mapNotNull { flow ->
                    val shifted =
                        if (flow.type == ForecastCashFlowType.EXPECTED_INCOME) {
                            flow.copy(date = flow.date.plusDays(incomeDelayDays.toLong()))
                        } else {
                            flow
                        }
                    shifted.takeIf { it.date <= endDate }
                }.groupBy(ForecastCashFlow::date)
        val projectedAllocations = input.goals.associate { it.id to it.allocatedAmount.amount }.toMutableMap()
        var totalAllocated = projectedAllocations.values.fold(BigDecimal.ZERO, BigDecimal::add)
        var balance = input.currentBalance.amount
        val days =
            List(input.horizonDays) { offset ->
                val date = input.startDate.plusDays(offset.toLong())
                val datedFlows = flows[date].orEmpty()
                val income = total(datedFlows, ForecastCashFlowType.EXPECTED_INCOME)
                val obligations = total(datedFlows, ForecastCashFlowType.OBLIGATION)
                val purchase = input.purchase?.takeIf { it.date == date }
                val purchaseAmount = purchase?.amount?.amount ?: BigDecimal.ZERO
                balance =
                    balance
                        .add(income)
                        .subtract(obligations)
                        .subtract(dailySpending.amount)
                        .subtract(purchaseAmount)
                val goalFunding = applyGoalFunding(purchase, projectedAllocations)
                totalAllocated = totalAllocated.subtract(goalFunding)
                val contributions =
                    input.goals
                        .sortedWith(compareBy(ForecastGoal::priority, ForecastGoal::deadline, ForecastGoal::id))
                        .flatMap { goal ->
                            goal.contributions
                                .filter { it.date.coerceAtLeast(input.startDate) == date && it.date <= goal.deadline }
                                .map { goal to it }
                        }
                contributions.forEach { (goal, contribution) ->
                    val allocated = checkNotNull(projectedAllocations[goal.id])
                    val remaining =
                        goal.targetAmount.amount
                            .subtract(allocated)
                            .max(BigDecimal.ZERO)
                    val available =
                        balance
                            .subtract(input.protectedReserve.amount)
                            .subtract(totalAllocated)
                            .max(BigDecimal.ZERO)
                    val funded =
                        contribution.amount.amount
                            .min(remaining)
                            .min(available)
                    projectedAllocations[goal.id] = allocated.add(funded)
                    totalAllocated = totalAllocated.add(funded)
                }
                ForecastDay(
                    date,
                    Money.of(income),
                    Money.of(obligations),
                    dailySpending,
                    Money.of(purchaseAmount),
                    Money.of(balance),
                    Money.of(goalFunding),
                    Money.of(totalAllocated),
                    projectedAllocations.mapValues { Money.of(it.value) },
                )
            }
        val goals =
            input.goals
                .sortedWith(compareBy(ForecastGoal::priority, ForecastGoal::deadline, ForecastGoal::id))
                .map { goal ->
                    goalForecast(
                        goal,
                        checkNotNull(projectedAllocations[goal.id]),
                        input.startDate,
                        endDate,
                    )
                }
        return ForecastScenario(
            days,
            Money.of(balance),
            days.minBy { it.closingBalance.amount }.closingBalance,
            days.firstOrNull { it.closingBalance.amount.signum() < 0 }?.date,
            goals,
        )
    }

    private fun total(
        flows: List<ForecastCashFlow>,
        type: ForecastCashFlowType,
    ): BigDecimal = flows.filter { it.type == type }.sumOf { it.amount.amount }

    private fun applyGoalFunding(
        purchase: ForecastPurchase?,
        projectedAllocations: MutableMap<UUID, BigDecimal>,
    ): BigDecimal {
        val goalId = purchase?.goalId ?: return BigDecimal.ZERO
        val allocated = checkNotNull(projectedAllocations[goalId])
        val funded = purchase.amount.amount.min(allocated)
        projectedAllocations[goalId] = allocated.subtract(funded)
        return funded
    }

    private fun goalForecast(
        goal: ForecastGoal,
        projectedAmount: BigDecimal,
        startDate: LocalDate,
        endDate: LocalDate,
    ): GoalForecast {
        val projected = Money.of(projectedAmount.min(goal.targetAmount.amount))
        val shortfall =
            Money.of(
                goal.targetAmount.amount
                    .subtract(projected.amount)
                    .max(BigDecimal.ZERO),
            )
        val reachability =
            when {
                goal.allocatedAmount.amount >= goal.targetAmount.amount -> GoalReachability.ACHIEVED
                goal.deadline < startDate -> GoalReachability.OVERDUE
                goal.deadline > endDate -> GoalReachability.OUTSIDE_HORIZON
                shortfall.amount.signum() == 0 -> GoalReachability.REACHABLE
                else -> GoalReachability.AT_RISK
            }
        return GoalForecast(goal, projected, shortfall, reachability)
    }

    private const val MONEY_SCALE = 2
    private val CAUTIOUS_SPENDING_MULTIPLIER = BigDecimal("1.20")
}
