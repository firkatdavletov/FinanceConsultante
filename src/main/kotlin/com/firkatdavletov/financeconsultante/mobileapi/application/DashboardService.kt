package com.firkatdavletov.financeconsultante.mobileapi.application

import com.firkatdavletov.financeconsultante.access.application.FamilyBudgetAccess
import com.firkatdavletov.financeconsultante.analytics.application.AnalyticsReportRepository
import com.firkatdavletov.financeconsultante.analytics.application.BudgetForecastRepository
import com.firkatdavletov.financeconsultante.analytics.application.ForecastAccountSnapshot
import com.firkatdavletov.financeconsultante.analytics.domain.BudgetForecast
import com.firkatdavletov.financeconsultante.analytics.domain.BudgetForecastCalculator
import com.firkatdavletov.financeconsultante.analytics.domain.BudgetForecastInput
import com.firkatdavletov.financeconsultante.analytics.domain.ForecastGoal
import com.firkatdavletov.financeconsultante.analytics.domain.ReportRange
import com.firkatdavletov.financeconsultante.budget.domain.BudgetCalculationInput
import com.firkatdavletov.financeconsultante.budget.domain.BudgetCalculator
import com.firkatdavletov.financeconsultante.budget.domain.BudgetEstimate
import com.firkatdavletov.financeconsultante.ledger.application.LedgerInteractionRepository
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.recurring.application.RecurringPlanService
import com.firkatdavletov.financeconsultante.recurring.domain.RecurringOccurrenceView
import com.firkatdavletov.financeconsultante.shared.domain.ACCOUNTING_ZONE
import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Service
class DashboardService(
    private val access: FamilyBudgetAccess,
    private val ledger: LedgerInteractionRepository,
    private val analytics: AnalyticsReportRepository,
    private val forecasts: BudgetForecastRepository,
    private val recurring: RecurringPlanService,
    private val clock: Clock,
) {
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    fun dashboard(telegramUserId: Long): DashboardSummary {
        val owner = ownedLedger(telegramUserId)
        val asOf = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val today = asOf.atZone(ACCOUNTING_ZONE).toLocalDate()
        val monthStart = today.withDayOfMonth(1)
        val monthEnd = today.withDayOfMonth(today.lengthOfMonth())
        val schedule = recurring.plannedExpenses(owner, today)
        val account = forecasts.account(owner, asOf) ?: throw MobileApiAccessDenied()
        if (!analytics.owns(owner)) throw MobileApiAccessDenied()

        val goals = forecasts.goals(owner.householdId, today, monthEnd)
        val budget = budget(account, schedule.expenses, goals, today, monthEnd)
        val actuals = analytics.aggregate(owner, ReportRange(monthStart, today.plusDays(1)))
        val forecast = forecast(owner, account, goals, today, monthEnd)
        val actualVariableSpending =
            forecasts.historicalVariableSpending(owner.householdId, monthStart, today.plusDays(1))

        return DashboardCalculator.calculate(
            DashboardCalculationInput(
                asOf,
                today,
                budget,
                actuals,
                actualVariableSpending,
                forecast,
                schedule.expenses,
            ),
        )
    }

    private fun budget(
        account: ForecastAccountSnapshot,
        expenses: List<RecurringOccurrenceView>,
        goals: List<ForecastGoal>,
        today: LocalDate,
        monthEnd: LocalDate,
    ): BudgetEstimate {
        val obligations =
            Money.of(
                expenses
                    .asSequence()
                    .filter { it.occurrence.dueOn <= monthEnd }
                    .fold(BigDecimal.ZERO) { total, expense -> total + expense.remainingAmount.amount },
                Currency.KZT,
            )
        val goalReserve =
            Money.of(goals.fold(BigDecimal.ZERO) { total, goal -> total + goal.allocatedAmount.amount }, Currency.KZT)
        return BudgetCalculator.calculate(
            BudgetCalculationInput(
                account.currentBalance,
                obligations,
                account.protectedReserve,
                goalReserve,
                today,
            ),
        )
    }

    private fun forecast(
        owner: OwnedLedger,
        account: ForecastAccountSnapshot,
        goals: List<ForecastGoal>,
        today: LocalDate,
        monthEnd: LocalDate,
    ): BudgetForecast {
        val historyStart =
            maxOf(
                today.minusDays(BudgetForecastCalculator.HISTORY_DAYS.toLong()),
                startedOn(owner.startedAt),
            )
        val historyDays = ChronoUnit.DAYS.between(historyStart, today).toInt()
        return BudgetForecastCalculator.calculate(
            BudgetForecastInput(
                today,
                account.currentBalance,
                account.protectedReserve,
                historyDays,
                forecasts.historicalVariableSpending(owner.householdId, historyStart, today),
                cashFlows = forecasts.cashFlows(owner.householdId, today, monthEnd.plusDays(1)),
                goals = goals,
                horizonDays = ChronoUnit.DAYS.between(today, monthEnd).toInt() + 1,
            ),
        )
    }

    private fun ownedLedger(telegramUserId: Long) =
        access.activeMember(telegramUserId)?.let { member ->
            ledger
                .owned(telegramUserId)
                ?.takeIf { it.householdId == member.householdId && it.memberId == member.id }
        } ?: throw MobileApiAccessDenied()

    private fun startedOn(startedAt: Instant): LocalDate = startedAt.atZone(ACCOUNTING_ZONE).toLocalDate()
}
