package com.firkatdavletov.financeconsultante.recurring.domain

import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

enum class IncomeFrequency { ONCE, WEEKLY, BIWEEKLY, MONTHLY }

const val MAX_PLAN_NAME_LENGTH = 160

fun planName(value: String): String =
    value.trim().also {
        require(it.isNotEmpty() && it.length <= MAX_PLAN_NAME_LENGTH) { "Invalid plan name" }
    }

data class IncomeRule(
    val id: UUID,
    val householdId: UUID,
    val memberId: UUID,
    val categoryId: UUID,
    val amount: Money,
    val dueOn: LocalDate,
    val incomeName: String,
    val incomeFrequency: IncomeFrequency,
    val incomeActive: Boolean,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val effectiveOn: LocalDate,
    val status: ExpectedIncomeStatus = ExpectedIncomeStatus.SCHEDULED,
) {
    init {
        amount.requirePositive()
        require(amount.currency == Currency.KZT)
        require(incomeName == planName(incomeName))
        require(version >= 0 && updatedAt >= createdAt)
    }

    @Suppress("ReturnCount") // Inactive, exhausted and out-of-horizon schedules terminate immediately.
    fun dueDatesThrough(through: LocalDate): List<LocalDate> {
        if (!incomeActive || status != ExpectedIncomeStatus.SCHEDULED) return emptyList()
        val dates = mutableListOf<LocalDate>()
        var index = 0L
        while (true) {
            val date =
                when (incomeFrequency) {
                    IncomeFrequency.ONCE -> {
                        if (index == 0L) dueOn else return dates
                    }

                    IncomeFrequency.WEEKLY -> {
                        dueOn.plusWeeks(index)
                    }

                    IncomeFrequency.BIWEEKLY -> {
                        dueOn.plusWeeks(index * 2)
                    }

                    IncomeFrequency.MONTHLY -> {
                        YearMonth.from(dueOn).plusMonths(index).let {
                            it.atDay(minOf(dueOn.dayOfMonth, it.lengthOfMonth()))
                        }
                    }
                }
            if (date > through) return dates
            if (date >= effectiveOn) dates += date
            index++
        }
    }
}
