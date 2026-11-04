package com.firkatdavletov.financeconsultante.recurring.application

import com.firkatdavletov.financeconsultante.analytics.application.BudgetForecastService
import com.firkatdavletov.financeconsultante.analytics.domain.BudgetForecast
import com.firkatdavletov.financeconsultante.analytics.domain.BudgetForecastCalculator
import com.firkatdavletov.financeconsultante.analytics.domain.BudgetForecastInput
import com.firkatdavletov.financeconsultante.analytics.domain.ForecastCashFlow
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.recurring.domain.DetectedRecurringExpense
import com.firkatdavletov.financeconsultante.recurring.domain.ExpenseObservation
import com.firkatdavletov.financeconsultante.recurring.domain.RecurrenceDetectionPolicy
import com.firkatdavletov.financeconsultante.recurring.domain.RecurringCandidateStatus
import com.firkatdavletov.financeconsultante.recurring.domain.RecurringExpenseCandidate
import com.firkatdavletov.financeconsultante.recurring.domain.RecurringExpenseDetector
import com.firkatdavletov.financeconsultante.recurring.domain.RecurringPriceIncrease
import com.firkatdavletov.financeconsultante.recurring.domain.RulePaymentObservation
import com.firkatdavletov.financeconsultante.shared.domain.ACCOUNTING_ZONE
import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

data class RecurringCandidateView(
    val candidate: RecurringExpenseCandidate,
    val categoryName: String,
    val evidence: List<ExpenseObservation>,
)

data class ForecastChange(
    val baselineBaseEnding: Money,
    val changedBaseEnding: Money,
    val baselineCautiousEnding: Money,
    val changedCautiousEnding: Money,
) {
    val baseDifference: Money
        get() = Money.of(changedBaseEnding.amount.subtract(baselineBaseEnding.amount), Currency.KZT)
}

data class CandidateAnalysis(
    val candidate: RecurringCandidateView,
    val previousTypicalAmount: Money?,
    val cancellationForecast: ForecastChange?,
)

data class PriceIncreaseAnalysis(
    val increase: RecurringPriceIncrease,
    val categoryName: String,
    val updateForecast: ForecastChange,
    val cancellationForecast: ForecastChange,
)

data class RecurringExpenseAnalysis(
    val historyStart: LocalDate,
    val observationCount: Int,
    val candidates: List<CandidateAnalysis>,
    val priceIncreases: List<PriceIncreaseAnalysis>,
    val requiredOccurrences: Int,
)

sealed interface CandidateDecision {
    data class Accepted(
        val rule: RecurringRuleView,
    ) : CandidateDecision

    data object Rejected : CandidateDecision

    data class Unavailable(
        val message: String,
    ) : CandidateDecision
}

@Suppress("TooManyFunctions") // Detection, confirmation, and forecast comparison form one owner workflow.
@Service
@Transactional
class RecurringExpenseAnalysisService(
    private val repository: RecurringExpenseAnalysisRepository,
    private val plans: RecurringPlanRepository,
    private val recurring: RecurringPlanService,
    private val forecasts: BudgetForecastService,
    private val policy: RecurrenceDetectionPolicy,
    private val clock: Clock,
) {
    fun analyze(owner: OwnedLedger): RecurringExpenseAnalysis {
        require(owner.canManageHousehold) { "Budget owner access required" }
        val today = today()
        val historyStart = maxOf(startedOn(owner), today.minusDays(policy.lookbackDays.toLong()))
        val observations = repository.observations(owner.householdId, historyStart, today.plusDays(1))
        val activeRules = plans.activeRules(owner.householdId)
        RecurringExpenseDetector
            .candidates(observations, activeRules, policy)
            .forEach { detected -> saveCandidate(owner, detected) }

        val projection = forecasts.projection(owner)
        val baseline = BudgetForecastCalculator.calculate(projection)
        val candidates =
            repository.pendingCandidates(owner.householdId, RESULT_LIMIT).mapNotNull { view ->
                val refreshed = RecurringExpenseDetector.candidates(view.evidence, emptyList(), policy).singleOrNull()
                if (refreshed == null || !view.candidate.matches(refreshed)) {
                    null
                } else {
                    CandidateAnalysis(
                        view,
                        refreshed.previousTypicalAmount,
                        candidateForecast(projection, baseline, view.evidence),
                    )
                }
            }
        val ruleViews = plans.rules(owner.householdId, RULE_LIMIT).associateBy { it.rule.id }
        val increases =
            RecurringExpenseDetector
                .priceIncreases(
                    activeRules,
                    repository.rulePayments(owner.householdId, historyStart, today.plusDays(1)),
                    policy,
                ).take(RESULT_LIMIT)
                .mapNotNull { increase ->
                    val view = ruleViews[increase.rule.id] ?: return@mapNotNull null
                    PriceIncreaseAnalysis(
                        increase,
                        view.categoryName,
                        compare(projection, baseline, changeRule(projection, increase.rule.id, increase.latestAmount)),
                        compare(projection, baseline, changeRule(projection, increase.rule.id, null)),
                    )
                }
        return RecurringExpenseAnalysis(
            historyStart,
            observations.size,
            candidates,
            increases,
            policy.minimumOccurrences,
        )
    }

    fun accept(
        owner: OwnedLedger,
        candidateId: UUID,
        version: Long,
    ): CandidateDecision {
        require(owner.canManageHousehold) { "Budget owner access required" }
        val view =
            repository.findCandidate(owner.householdId, candidateId, lock = true)
                ?: return CandidateDecision.Unavailable(STALE_CANDIDATE)
        val candidate = view.candidate
        val evidence = repository.activeEvidence(owner.householdId, candidateId)
        val detected = RecurringExpenseDetector.candidates(evidence, emptyList(), policy).singleOrNull()
        val unavailableReason = unavailableReason(owner, candidate, version, evidence, detected)
        return if (unavailableReason != null) {
            if (candidate.status == RecurringCandidateStatus.PENDING && candidate.version == version) {
                check(repository.reject(candidate, now())) { "Candidate changed while locked" }
            }
            CandidateDecision.Unavailable(unavailableReason)
        } else {
            createRule(owner, candidate)
        }
    }

    private fun unavailableReason(
        owner: OwnedLedger,
        candidate: RecurringExpenseCandidate,
        version: Long,
        evidence: List<ExpenseObservation>,
        detected: DetectedRecurringExpense?,
    ): String? =
        when {
            candidate.status != RecurringCandidateStatus.PENDING || candidate.version != version -> {
                STALE_CANDIDATE
            }

            evidence.size < policy.minimumOccurrences || detected == null || !candidate.matches(detected) -> {
                INVALID_EVIDENCE
            }

            plans.activeRules(owner.householdId).any {
                RecurringExpenseDetector.isDuplicate(it, detected, policy)
            } -> {
                DUPLICATE_RULE
            }

            else -> {
                null
            }
        }

    private fun createRule(
        owner: OwnedLedger,
        candidate: RecurringExpenseCandidate,
    ): CandidateDecision =
        when (
            val created =
                recurring.createRule(
                    owner,
                    candidate.frequency,
                    candidate.scheduleValue,
                    candidate.suggestedAmount,
                    candidate.categoryId,
                    today(),
                    candidate.normalizedDescription,
                )
        ) {
            is PlanChange.Rejected -> {
                CandidateDecision.Unavailable(created.message)
            }

            is PlanChange.Applied -> {
                check(repository.accept(candidate, created.value.rule.id, now())) { "Candidate changed while locked" }
                CandidateDecision.Accepted(created.value)
            }
        }

    fun reject(
        owner: OwnedLedger,
        candidateId: UUID,
        version: Long,
    ): CandidateDecision {
        require(owner.canManageHousehold) { "Budget owner access required" }
        val candidate = repository.findCandidate(owner.householdId, candidateId, lock = true)?.candidate
        return if (
            candidate == null ||
            candidate.status != RecurringCandidateStatus.PENDING ||
            candidate.version != version
        ) {
            CandidateDecision.Unavailable(STALE_CANDIDATE)
        } else if (repository.reject(candidate, now())) {
            CandidateDecision.Rejected
        } else {
            CandidateDecision.Unavailable(STALE_CANDIDATE)
        }
    }

    private fun saveCandidate(
        owner: OwnedLedger,
        detected: DetectedRecurringExpense,
    ) {
        val now = now()
        repository.saveCandidate(
            RecurringExpenseCandidate(
                UUID.randomUUID(),
                owner.householdId,
                owner.memberId,
                detected.categoryId,
                detected.normalizedDescription,
                detected.frequency,
                detected.scheduleValue,
                detected.suggestedAmount,
                createdAt = now,
            ),
            detected.evidence,
            now,
        )
    }

    private fun candidateForecast(
        input: BudgetForecastInput,
        baseline: BudgetForecast,
        evidence: List<ExpenseObservation>,
    ): ForecastChange? {
        val canCompare =
            input.historyDays >= BudgetForecastCalculator.MIN_HISTORY_DAYS &&
                input.manualDailySpending == null
        val start = input.startDate.minusDays(input.historyDays.toLong())
        val recurringHistory =
            if (canCompare) {
                evidence
                    .filter { it.occurredOn >= start && it.occurredOn < input.startDate }
                    .fold(BigDecimal.ZERO) { total, item -> total.add(item.amount.amount) }
            } else {
                BigDecimal.ZERO
            }
        return if (recurringHistory.signum() == 0) {
            null
        } else {
            val adjusted =
                input.copy(
                    historicalVariableSpending =
                        Money.of(
                            input.historicalVariableSpending.amount
                                .subtract(recurringHistory)
                                .max(BigDecimal.ZERO),
                        ),
                )
            compare(input, baseline, adjusted)
        }
    }

    private fun changeRule(
        input: BudgetForecastInput,
        ruleId: UUID,
        amount: Money?,
    ): BudgetForecastInput =
        input.copy(
            cashFlows =
                input.cashFlows.mapNotNull { flow ->
                    if (flow.recurringRuleId != ruleId || !flow.adjustable) {
                        flow
                    } else {
                        amount?.let { flow.copy(amount = it) }
                    }
                },
        )

    private fun compare(
        original: BudgetForecastInput,
        baseline: BudgetForecast,
        changed: BudgetForecastInput,
    ): ForecastChange {
        require(original.startDate == changed.startDate)
        val alternative = BudgetForecastCalculator.calculate(changed)
        return ForecastChange(
            baseline.base.endingBalance,
            alternative.base.endingBalance,
            baseline.cautious.endingBalance,
            alternative.cautious.endingBalance,
        )
    }

    private fun RecurringExpenseCandidate.matches(detected: DetectedRecurringExpense): Boolean =
        categoryId == detected.categoryId &&
            normalizedDescription == detected.normalizedDescription &&
            frequency == detected.frequency &&
            scheduleValue == detected.scheduleValue &&
            suggestedAmount == detected.suggestedAmount

    private fun startedOn(owner: OwnedLedger): LocalDate = owner.startedAt.atZone(ACCOUNTING_ZONE).toLocalDate()

    private fun today(): LocalDate = LocalDate.now(clock.withZone(ACCOUNTING_ZONE))

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    companion object {
        private const val RESULT_LIMIT = 3
        private const val RULE_LIMIT = 100
        private const val STALE_CANDIDATE =
            "Предложение изменилось, уже обработано или недоступно. Запустите /recurring analyze."
        private const val INVALID_EVIDENCE =
            "Подтверждающие операции изменились или были возвращены. Предложение отклонено; запустите анализ позже."
        private const val DUPLICATE_RULE =
            "Такое постоянное обязательство уже существует. Новое правило не создано."
    }
}

interface RecurringExpenseAnalysisRepository {
    fun observations(
        householdId: UUID,
        startInclusive: LocalDate,
        endExclusive: LocalDate,
    ): List<ExpenseObservation>

    fun rulePayments(
        householdId: UUID,
        startInclusive: LocalDate,
        endExclusive: LocalDate,
    ): List<RulePaymentObservation>

    fun saveCandidate(
        candidate: RecurringExpenseCandidate,
        evidence: List<ExpenseObservation>,
        now: Instant,
    ): Boolean

    fun pendingCandidates(
        householdId: UUID,
        limit: Int,
    ): List<RecurringCandidateView>

    fun findCandidate(
        householdId: UUID,
        candidateId: UUID,
        lock: Boolean = false,
    ): RecurringCandidateView?

    fun activeEvidence(
        householdId: UUID,
        candidateId: UUID,
    ): List<ExpenseObservation>

    fun accept(
        candidate: RecurringExpenseCandidate,
        ruleId: UUID,
        now: Instant,
    ): Boolean

    fun reject(
        candidate: RecurringExpenseCandidate,
        now: Instant,
    ): Boolean
}
