package com.firkatdavletov.financeconsultante.analytics.application

import com.firkatdavletov.financeconsultante.analytics.domain.BudgetForecast
import com.firkatdavletov.financeconsultante.analytics.domain.BudgetForecastCalculator
import com.firkatdavletov.financeconsultante.analytics.domain.BudgetForecastInput
import com.firkatdavletov.financeconsultante.analytics.domain.ForecastCashFlow
import com.firkatdavletov.financeconsultante.analytics.domain.ForecastGoal
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.recurring.application.RecurringPlanService
import com.firkatdavletov.financeconsultante.shared.domain.Money
import com.firkatdavletov.financeconsultante.telegram.domain.ACCOUNTING_ZONE
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class ForecastAccountSnapshot(
    val currentBalance: Money,
    val protectedReserve: Money,
)

interface BudgetForecastRepository {
    fun account(
        owner: OwnedLedger,
        asOf: Instant,
    ): ForecastAccountSnapshot?

    fun historicalVariableSpending(
        householdId: java.util.UUID,
        startInclusive: LocalDate,
        endExclusive: LocalDate,
    ): Money

    fun cashFlows(
        householdId: java.util.UUID,
        startInclusive: LocalDate,
        endExclusive: LocalDate,
    ): List<ForecastCashFlow>

    fun goals(
        householdId: java.util.UUID,
        startInclusive: LocalDate,
        throughInclusive: LocalDate,
    ): List<ForecastGoal>
}

@Service
@Transactional(
    isolation = Isolation.REPEATABLE_READ,
    propagation = Propagation.REQUIRES_NEW,
)
class BudgetForecastService(
    private val repository: BudgetForecastRepository,
    private val recurring: RecurringPlanService,
    private val clock: Clock,
) {
    fun forecast(
        owner: OwnedLedger,
        manualDailySpending: Money? = null,
    ): BudgetForecast = BudgetForecastCalculator.calculate(projection(owner, manualDailySpending))

    fun projection(
        owner: OwnedLedger,
        manualDailySpending: Money? = null,
        horizonDays: Int = BudgetForecastCalculator.HORIZON_DAYS,
    ): BudgetForecastInput {
        require(horizonDays > 0)
        val asOf = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val today = asOf.atZone(ACCOUNTING_ZONE).toLocalDate()
        val endExclusive = today.plusDays(horizonDays.toLong())
        val account = requireNotNull(repository.account(owner, asOf)) { "Budget access denied" }
        recurring.ensureOccurrences(owner, endExclusive.minusDays(1))
        val historyStart = maxOf(today.minusDays(BudgetForecastCalculator.HISTORY_DAYS.toLong()), startedOn(owner))
        val historyDays = ChronoUnit.DAYS.between(historyStart, today).toInt()
        val input =
            BudgetForecastInput(
                today,
                account.currentBalance,
                account.protectedReserve,
                historyDays,
                repository.historicalVariableSpending(owner.householdId, historyStart, today),
                manualDailySpending,
                repository.cashFlows(owner.householdId, today, endExclusive),
                repository.goals(owner.householdId, today, endExclusive.minusDays(1)),
                horizonDays,
            )
        return input
    }

    private fun startedOn(owner: OwnedLedger): LocalDate = owner.startedAt.atZone(ACCOUNTING_ZONE).toLocalDate()
}
