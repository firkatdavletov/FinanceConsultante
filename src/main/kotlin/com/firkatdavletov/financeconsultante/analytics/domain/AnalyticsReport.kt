package com.firkatdavletov.financeconsultante.analytics.domain

import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.UUID

enum class AnalyticsPeriod {
    WEEK,
    MONTH,
    ;

    fun startOf(date: LocalDate): LocalDate =
        when (this) {
            WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            MONTH -> date.withDayOfMonth(1)
        }

    fun endOf(start: LocalDate): LocalDate =
        when (this) {
            WEEK -> start.plusWeeks(1)
            MONTH -> start.plusMonths(1)
        }

    fun previousStart(start: LocalDate): LocalDate =
        when (this) {
            WEEK -> start.minusWeeks(1)
            MONTH -> start.minusMonths(1)
        }
}

data class ReportRange(
    val startInclusive: LocalDate,
    val endExclusive: LocalDate,
) {
    init {
        require(startInclusive < endExclusive) { "Report range must not be empty" }
    }

    val days: Long get() = ChronoUnit.DAYS.between(startInclusive, endExclusive)
}

data class ReportRanges(
    val current: ReportRange,
    val previous: ReportRange,
) {
    companion object {
        fun current(
            period: AnalyticsPeriod,
            today: LocalDate,
        ): ReportRanges {
            val currentStart = period.startOf(today)
            val currentEnd = today.plusDays(1)
            val previousStart = period.previousStart(currentStart)
            val previousPeriodEnd = currentStart
            val previousEnd = previousStart.plusDays(ChronoUnit.DAYS.between(currentStart, currentEnd))
            return ReportRanges(
                ReportRange(currentStart, currentEnd),
                ReportRange(previousStart, minOf(previousEnd, previousPeriodEnd)),
            )
        }

        fun completed(
            period: AnalyticsPeriod,
            periodStart: LocalDate,
        ): ReportRanges {
            require(period.startOf(periodStart) == periodStart) { "Report period start is not aligned" }
            val previousStart = period.previousStart(periodStart)
            return ReportRanges(
                ReportRange(periodStart, period.endOf(periodStart)),
                ReportRange(previousStart, periodStart),
            )
        }
    }
}

data class CategoryAnalytics(
    val categoryId: UUID,
    val name: String,
    val expenses: Money,
    val refunds: Money,
) {
    init {
        require(name.isNotBlank())
        require(expenses.currency == Currency.KZT && refunds.currency == Currency.KZT)
        require(expenses.amount.signum() >= 0 && refunds.amount.signum() >= 0)
    }

    val netExpenses: Money get() = expenses - refunds
}

data class PeriodAnalytics(
    val income: Money,
    val expenses: Money,
    val refunds: Money,
    val categories: List<CategoryAnalytics>,
) {
    init {
        require(listOf(income, expenses, refunds).all { it.currency == Currency.KZT && it.amount.signum() >= 0 })
        require(categories.sumOf { it.expenses.amount }.compareTo(expenses.amount) == 0) {
            "Category expenses do not match total expenses"
        }
        require(categories.sumOf { it.refunds.amount }.compareTo(refunds.amount) == 0) {
            "Category refunds do not match total refunds"
        }
    }

    val netExpenses: Money get() = expenses - refunds

    val empty: Boolean
        get() = income.amount.signum() == 0 && expenses.amount.signum() == 0 && refunds.amount.signum() == 0

    companion object {
        fun empty(): PeriodAnalytics = PeriodAnalytics(Money.of("0"), Money.of("0"), Money.of("0"), emptyList())
    }
}

data class AnalyticsReport(
    val period: AnalyticsPeriod,
    val ranges: ReportRanges,
    val current: PeriodAnalytics,
    val previous: PeriodAnalytics,
    val asOf: Instant,
    val accountingStartedAt: Instant,
    val completedPeriod: Boolean,
    val currentComplete: Boolean,
    val comparisonComplete: Boolean,
) {
    init {
        require(!comparisonComplete || currentComplete)
        require(asOf >= accountingStartedAt)
    }

    fun netExpenseChange(): AnalyticsChange = change(current.netExpenses, previous.netExpenses)

    fun categoryChange(categoryId: UUID): AnalyticsChange {
        val currentAmount = current.categories.singleOrNull { it.categoryId == categoryId }?.netExpenses ?: ZERO
        val previousAmount = previous.categories.singleOrNull { it.categoryId == categoryId }?.netExpenses ?: ZERO
        return change(currentAmount, previousAmount)
    }

    private fun change(
        currentAmount: Money,
        previousAmount: Money,
    ): AnalyticsChange {
        val difference = currentAmount - previousAmount
        val percentage =
            if (comparisonComplete && previousAmount.amount.signum() > 0) {
                difference.amount
                    .multiply(HUNDRED)
                    .divide(previousAmount.amount, PERCENT_SCALE, RoundingMode.HALF_UP)
            } else {
                null
            }
        return AnalyticsChange(currentAmount, previousAmount, difference, percentage)
    }

    companion object {
        private val ZERO = Money.of("0")
        private val HUNDRED = BigDecimal("100")
        private const val PERCENT_SCALE = 1
    }
}

data class AnalyticsChange(
    val current: Money,
    val previous: Money,
    val difference: Money,
    val percentage: BigDecimal?,
)
