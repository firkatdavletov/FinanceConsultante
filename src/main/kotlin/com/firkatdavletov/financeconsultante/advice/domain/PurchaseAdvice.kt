package com.firkatdavletov.financeconsultante.advice.domain

import com.firkatdavletov.financeconsultante.analytics.domain.BudgetForecast
import com.firkatdavletov.financeconsultante.analytics.domain.ForecastScenario
import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

enum class PurchaseNecessity {
    NECESSARY,
    OPTIONAL,
    UNSPECIFIED,
}

data class PurchaseRequest(
    val amount: Money,
    val purchaseOn: LocalDate,
    val necessity: PurchaseNecessity = PurchaseNecessity.UNSPECIFIED,
    val goalId: UUID? = null,
    val manualDailySpending: Money? = null,
    val dateWasAssumed: Boolean = false,
) {
    init {
        require(amount.currency == Currency.KZT && amount.amount.signum() > 0)
        manualDailySpending?.let {
            require(it.currency == Currency.KZT && it.amount.signum() > 0)
        }
    }
}

data class PurchaseGoalImpact(
    val id: UUID,
    val name: String,
    val shortfallBefore: Money,
    val shortfallAfter: Money,
) {
    val additionalShortfall: Money
        get() = Money.of(shortfallAfter.amount.subtract(shortfallBefore.amount).max(BigDecimal.ZERO))
}

data class PurchaseScenarioAssessment(
    val purchaseOn: LocalDate,
    val cashAfterPurchase: Money,
    val remainingObligations: Money,
    val protectedReserve: Money,
    val goalReserve: Money,
    val freeAfterPurchase: Money,
    val dailyLimitAfterPurchase: Money,
    val goalFunding: Money,
    val minimumCashBalance: Money,
    val endingBalance: Money,
    val firstCashDeficitDate: LocalDate?,
    val firstReserveBreachDate: LocalDate?,
    val goalImpacts: List<PurchaseGoalImpact>,
) {
    val affordable: Boolean
        get() =
            freeAfterPurchase.amount.signum() >= 0 &&
                firstCashDeficitDate == null &&
                firstReserveBreachDate == null
}

data class PurchaseScenarioPair(
    val base: PurchaseScenarioAssessment,
    val cautious: PurchaseScenarioAssessment,
)

object PurchaseAdviceCalculator {
    fun assess(
        baseline: BudgetForecast,
        purchased: BudgetForecast,
        purchaseOn: LocalDate,
    ): PurchaseScenarioPair {
        require(baseline.startDate == purchased.startDate && baseline.endDate == purchased.endDate)
        return PurchaseScenarioPair(
            scenario(baseline, baseline.base, purchased.base, purchaseOn),
            scenario(baseline, baseline.cautious, purchased.cautious, purchaseOn),
        )
    }

    private fun scenario(
        forecast: BudgetForecast,
        baseline: ForecastScenario,
        purchased: ForecastScenario,
        purchaseOn: LocalDate,
    ): PurchaseScenarioAssessment {
        val index = purchased.days.indexOfFirst { it.date == purchaseOn }
        require(index >= 0) { "Purchase date is outside the forecast" }
        val day = purchased.days[index]
        val openingBalance = if (index == 0) forecast.currentBalance else purchased.days[index - 1].closingBalance
        val cashAfterPurchase = openingBalance + day.expectedIncome - day.obligations - day.purchase
        val monthEnd = purchaseOn.withDayOfMonth(purchaseOn.lengthOfMonth())
        val remainingObligations =
            Money.of(
                purchased.days
                    .asSequence()
                    .filter { it.date > purchaseOn && it.date <= monthEnd }
                    .fold(BigDecimal.ZERO) { total, item -> total + item.obligations.amount },
            )
        val free = cashAfterPurchase - remainingObligations - forecast.protectedReserve - day.goalReserve
        val dailyLimit =
            Money.of(
                free.amount
                    .max(BigDecimal.ZERO)
                    .divide(BigDecimal(purchaseOn.lengthOfMonth() - purchaseOn.dayOfMonth + 1), 0, RoundingMode.FLOOR),
            )
        val firstReserveBreach =
            purchased.days
                .firstOrNull {
                    it.closingBalance.amount
                        .subtract(forecast.protectedReserve.amount)
                        .subtract(it.goalReserve.amount)
                        .signum() < 0
                }?.date
        val baselineGoals = baseline.goals.associateBy { it.goal.id }
        val impacts =
            purchased.goals.mapNotNull { after ->
                val before = baselineGoals[after.goal.id] ?: return@mapNotNull null
                PurchaseGoalImpact(
                    after.goal.id,
                    after.goal.name,
                    before.shortfall,
                    after.shortfall,
                ).takeIf { it.additionalShortfall.amount.signum() > 0 }
            }
        return PurchaseScenarioAssessment(
            purchaseOn,
            cashAfterPurchase,
            remainingObligations,
            forecast.protectedReserve,
            day.goalReserve,
            free,
            dailyLimit,
            day.goalFunding,
            purchased.minimumDailyBalance,
            purchased.endingBalance,
            purchased.firstDeficitDate,
            firstReserveBreach,
            impacts,
        )
    }
}
