package com.firkatdavletov.financeconsultante.advice.application

import com.firkatdavletov.financeconsultante.advice.domain.PurchaseAdviceCalculator
import com.firkatdavletov.financeconsultante.advice.domain.PurchaseRequest
import com.firkatdavletov.financeconsultante.advice.domain.PurchaseScenarioPair
import com.firkatdavletov.financeconsultante.analytics.application.BudgetForecastService
import com.firkatdavletov.financeconsultante.analytics.domain.BudgetForecast
import com.firkatdavletov.financeconsultante.analytics.domain.BudgetForecastCalculator
import com.firkatdavletov.financeconsultante.analytics.domain.ForecastPurchase
import com.firkatdavletov.financeconsultante.analytics.domain.SpendingEstimateSource
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.telegram.domain.ACCOUNTING_ZONE
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit

sealed interface PurchaseAdviceResult {
    data class Ready(
        val advice: PurchaseAdvice,
    ) : PurchaseAdviceResult

    data class NeedsInput(
        val message: String,
    ) : PurchaseAdviceResult
}

data class PurchaseAdvice(
    val request: PurchaseRequest,
    val goalName: String?,
    val baseline: BudgetForecast,
    val purchase: PurchaseScenarioPair,
    val saferDate: LocalDate?,
)

@Service
class PurchaseAdviceService(
    private val forecasts: BudgetForecastService,
    private val clock: Clock,
) {
    @Suppress("ReturnCount") // Invalid access-scoped inputs stop before the deterministic simulation.
    fun analyze(
        owner: OwnedLedger,
        request: PurchaseRequest,
    ): PurchaseAdviceResult {
        val today = LocalDate.now(clock.withZone(ACCOUNTING_ZONE))
        val lastPurchaseDate = today.plusDays(PURCHASE_WINDOW_DAYS - 1L)
        if (request.purchaseOn !in today..lastPurchaseDate) {
            return PurchaseAdviceResult.NeedsInput(
                "Дата покупки должна быть от $today до $lastPurchaseDate — в пределах 30 дней.",
            )
        }
        val projectionEnd = lastPurchaseDate.withDayOfMonth(lastPurchaseDate.lengthOfMonth())
        val horizonDays = ChronoUnit.DAYS.between(today, projectionEnd).toInt() + 1
        val input = forecasts.projection(owner, request.manualDailySpending, horizonDays)
        val goal = request.goalId?.let { id -> input.goals.singleOrNull { it.id == id } }
        if (request.goalId != null && goal == null) {
            return PurchaseAdviceResult.NeedsInput("Цель не найдена в этом бюджете. Проверьте ID командой /goals.")
        }
        val baseline = BudgetForecastCalculator.calculate(input)
        if (baseline.spending.source == SpendingEstimateSource.UNAVAILABLE) {
            return PurchaseAdviceResult.NeedsInput(
                "Для оценки обычных трат есть только ${baseline.spending.historyDays} завершённых дней; " +
                    "нужно минимум ${BudgetForecastCalculator.MIN_HISTORY_DAYS}. " +
                    "Повторите команду с daily=<положительный лимит KZT в день>.",
            )
        }
        val requested = scenario(input, baseline, request, request.purchaseOn)
        val saferDate =
            if (requested.cautious.affordable) {
                null
            } else {
                generateSequence(request.purchaseOn.plusDays(1)) { it.plusDays(1) }
                    .takeWhile { it <= lastPurchaseDate }
                    .firstOrNull { scenario(input, baseline, request, it).cautious.affordable }
            }
        return PurchaseAdviceResult.Ready(
            PurchaseAdvice(request, goal?.name, baseline, requested, saferDate),
        )
    }

    private fun scenario(
        input: com.firkatdavletov.financeconsultante.analytics.domain.BudgetForecastInput,
        baseline: BudgetForecast,
        request: PurchaseRequest,
        purchaseOn: LocalDate,
    ): PurchaseScenarioPair {
        val purchased =
            BudgetForecastCalculator.calculate(
                input.copy(purchase = ForecastPurchase(purchaseOn, request.amount, request.goalId)),
            )
        return PurchaseAdviceCalculator.assess(baseline, purchased, purchaseOn)
    }

    companion object {
        const val PURCHASE_WINDOW_DAYS = 30L
    }
}
