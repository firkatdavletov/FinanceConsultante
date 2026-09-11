package com.firkatdavletov.financeconsultante.budget.domain

import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

enum class BudgetState {
    INCOMPLETE,
    DEFICIT,
    NO_FREE_FUNDS,
    AVAILABLE,
}

data class BudgetCalculationInput(
    val balance: Money?,
    val remainingObligations: Money,
    val protectedReserve: Money,
    val goalReserve: Money,
    val date: LocalDate,
    val obligationsKnown: Boolean = true,
) {
    init {
        require(
            listOfNotNull(balance, remainingObligations, protectedReserve, goalReserve).all {
                it.currency == Currency.KZT
            },
        )
        require(remainingObligations.amount.signum() >= 0)
        require(protectedReserve.amount.signum() >= 0)
        require(goalReserve.amount.signum() >= 0)
    }
}

data class BudgetEstimate(
    val state: BudgetState,
    val balance: Money?,
    val remainingObligations: Money,
    val protectedReserve: Money,
    val goalReserve: Money,
    val freeAmount: Money?,
    val daysRemaining: Int,
    val dailyLimit: Money?,
) {
    val deficit: Money?
        get() = freeAmount?.takeIf { it.amount.signum() < 0 }?.let { Money.of(it.amount.negate()) }
}

object BudgetCalculator {
    fun calculate(input: BudgetCalculationInput): BudgetEstimate {
        val days = input.date.lengthOfMonth() - input.date.dayOfMonth + 1
        if (input.balance == null || !input.obligationsKnown) {
            return BudgetEstimate(
                BudgetState.INCOMPLETE,
                input.balance,
                input.remainingObligations,
                input.protectedReserve,
                input.goalReserve,
                null,
                days,
                null,
            )
        }
        val free = input.balance - input.remainingObligations - input.protectedReserve - input.goalReserve
        val state =
            when (free.amount.signum()) {
                -1 -> BudgetState.DEFICIT
                0 -> BudgetState.NO_FREE_FUNDS
                else -> BudgetState.AVAILABLE
            }
        val available = free.amount.max(BigDecimal.ZERO)
        val limit = Money.of(available.divide(BigDecimal(days), 0, RoundingMode.FLOOR))
        return BudgetEstimate(
            state,
            input.balance,
            input.remainingObligations,
            input.protectedReserve,
            input.goalReserve,
            free,
            days,
            limit,
        )
    }
}
