package com.firkatdavletov.financeconsultante.budget.domain

import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class DailyBudgetSnapshot(
    val householdId: UUID,
    val date: LocalDate,
    val asOf: Instant,
    val balance: Money,
    val remainingObligations: Money,
    val protectedReserve: Money,
    val goalReserve: Money,
    val freeAmount: Money,
    val daysRemaining: Int,
    val originalDailyLimit: Money,
    val ledgerRevision: Long,
    val settingsVersion: Long,
    val recalculated: Boolean = false,
) {
    init {
        require(
            listOf(
                balance,
                remainingObligations,
                protectedReserve,
                goalReserve,
                freeAmount,
                originalDailyLimit,
            ).all { it.currency == Currency.KZT },
        )
        require(remainingObligations.amount.signum() >= 0)
        require(protectedReserve.amount.signum() >= 0 && goalReserve.amount.signum() >= 0)
        require(originalDailyLimit.amount.signum() >= 0)
        require(daysRemaining in 1..date.lengthOfMonth() && ledgerRevision >= 0 && settingsVersion >= 0)
    }
}

data class DailyBudgetView(
    val snapshot: DailyBudgetSnapshot,
    val current: BudgetEstimate,
    val spentAfterSnapshot: Money,
    val remainingDailyLimit: Money,
) {
    val currentFreeAmount: Money
        get() = checkNotNull(current.freeAmount)
}
