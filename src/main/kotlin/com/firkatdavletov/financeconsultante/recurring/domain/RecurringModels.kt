package com.firkatdavletov.financeconsultante.recurring.domain

import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

enum class RecurrenceFrequency {
    MONTHLY,
    WEEKLY,
}

data class RecurringRule(
    val id: UUID,
    val householdId: UUID,
    val memberId: UUID,
    val categoryId: UUID,
    val frequency: RecurrenceFrequency,
    val scheduleValue: Int,
    val amount: Money,
    val startsOn: LocalDate,
    val active: Boolean = true,
    val version: Long = 0,
    val createdAt: Instant,
    val updatedAt: Instant = createdAt,
) {
    init {
        amount.requirePositive()
        require(amount.currency == Currency.KZT) { "Planned amounts must use the base currency" }
        require(
            when (frequency) {
                RecurrenceFrequency.MONTHLY -> scheduleValue in MIN_MONTH_DAY..MAX_MONTH_DAY
                RecurrenceFrequency.WEEKLY -> scheduleValue in MIN_WEEK_DAY..MAX_WEEK_DAY
            },
        ) { "Invalid recurrence schedule" }
        require(version >= 0 && updatedAt >= createdAt) { "Invalid recurring rule version" }
    }

    fun dueDatesThrough(throughInclusive: LocalDate): List<LocalDate> {
        if (throughInclusive < startsOn) return emptyList()
        return when (frequency) {
            RecurrenceFrequency.MONTHLY -> monthlyDates(throughInclusive)
            RecurrenceFrequency.WEEKLY -> weeklyDates(throughInclusive)
        }
    }

    private fun monthlyDates(throughInclusive: LocalDate): List<LocalDate> {
        val dates = mutableListOf<LocalDate>()
        var month = YearMonth.from(startsOn)
        val lastMonth = YearMonth.from(throughInclusive)
        while (month <= lastMonth) {
            val dueOn = month.atDay(scheduleValue.coerceAtMost(month.lengthOfMonth()))
            if (dueOn >= startsOn && dueOn <= throughInclusive) dates += dueOn
            month = month.plusMonths(1)
        }
        return dates
    }

    private fun weeklyDates(throughInclusive: LocalDate): List<LocalDate> {
        val day = DayOfWeek.of(scheduleValue)
        val shift = (day.value - startsOn.dayOfWeek.value + DAYS_IN_WEEK) % DAYS_IN_WEEK
        val dates = mutableListOf<LocalDate>()
        var dueOn = startsOn.plusDays(shift.toLong())
        while (dueOn <= throughInclusive) {
            dates += dueOn
            dueOn = dueOn.plusWeeks(1)
        }
        return dates
    }

    companion object {
        private const val DAYS_IN_WEEK = 7
        private const val MIN_MONTH_DAY = 1
        private const val MAX_MONTH_DAY = 31
        private const val MIN_WEEK_DAY = 1
        private const val MAX_WEEK_DAY = 7
    }
}

enum class OccurrenceStatus {
    SCHEDULED,
    CANCELLED,
}

data class RecurringOccurrence(
    val id: UUID,
    val householdId: UUID,
    val ruleId: UUID,
    val categoryId: UUID,
    val plannedAmount: Money,
    val dueOn: LocalDate,
    val status: OccurrenceStatus = OccurrenceStatus.SCHEDULED,
    val version: Long = 0,
    val createdAt: Instant,
    val updatedAt: Instant = createdAt,
) {
    init {
        plannedAmount.requirePositive()
        require(plannedAmount.currency == Currency.KZT) { "Planned amounts must use the base currency" }
        require(version >= 0 && updatedAt >= createdAt) { "Invalid occurrence version" }
    }
}

data class RecurringOccurrenceView(
    val occurrence: RecurringOccurrence,
    val categoryName: String,
    val paidAmount: Money,
) {
    init {
        require(paidAmount.currency == occurrence.plannedAmount.currency) { "Payment currency mismatch" }
        require(paidAmount.amount.signum() >= 0) { "Paid amount cannot be negative" }
    }

    val remainingAmount: Money
        get() =
            if (occurrence.status == OccurrenceStatus.CANCELLED) {
                Money.of(BigDecimal.ZERO, occurrence.plannedAmount.currency)
            } else {
                Money.of(
                    occurrence.plannedAmount.amount
                        .subtract(paidAmount.amount)
                        .max(BigDecimal.ZERO),
                    occurrence.plannedAmount.currency,
                )
            }

    fun overdue(on: LocalDate): Boolean =
        occurrence.status == OccurrenceStatus.SCHEDULED &&
            remainingAmount.amount.signum() > 0 && occurrence.dueOn < on

    val paidInFull: Boolean
        get() = occurrence.status == OccurrenceStatus.SCHEDULED && remainingAmount.amount.signum() == 0
}

enum class ExpectedIncomeStatus {
    SCHEDULED,
    CANCELLED,
}

data class ExpectedIncome(
    val id: UUID,
    val householdId: UUID,
    val memberId: UUID,
    val categoryId: UUID,
    val plannedAmount: Money,
    val dueOn: LocalDate,
    val status: ExpectedIncomeStatus = ExpectedIncomeStatus.SCHEDULED,
    val version: Long = 0,
    val createdAt: Instant,
    val updatedAt: Instant = createdAt,
) {
    init {
        plannedAmount.requirePositive()
        require(plannedAmount.currency == Currency.KZT) { "Planned amounts must use the base currency" }
        require(version >= 0 && updatedAt >= createdAt) { "Invalid expected income version" }
    }
}

data class ExpectedIncomeView(
    val expectedIncome: ExpectedIncome,
    val categoryName: String,
    val receivedAmount: Money,
    val postedMatches: Int,
) {
    init {
        require(receivedAmount.currency == expectedIncome.plannedAmount.currency) { "Income currency mismatch" }
        require(receivedAmount.amount.signum() >= 0 && postedMatches >= 0) { "Invalid received income" }
    }

    val fulfilled: Boolean
        get() = postedMatches > 0

    val forecastAmount: Money
        get() =
            if (expectedIncome.status == ExpectedIncomeStatus.SCHEDULED && !fulfilled) {
                expectedIncome.plannedAmount
            } else {
                Money.of(BigDecimal.ZERO, expectedIncome.plannedAmount.currency)
            }
}
