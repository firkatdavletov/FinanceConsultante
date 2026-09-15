package com.firkatdavletov.financeconsultante.recurring.domain

import com.firkatdavletov.financeconsultante.ledger.domain.ExpenseDescriptionNormalizer
import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

data class RecurrenceDetectionPolicy(
    val minimumOccurrences: Int,
    val lookbackDays: Int,
    val weeklyIntervalDays: Int,
    val weeklyToleranceDays: Int,
    val monthlyIntervalDays: Int,
    val monthlyToleranceDays: Int,
    val amountTolerancePercent: Int,
    val priceIncreasePercent: Int,
    val allowedSkippedPeriods: Int,
) {
    init {
        require(minimumOccurrences >= MIN_REQUIRED_OCCURRENCES)
        require(lookbackDays >= monthlyIntervalDays * minimumOccurrences)
        require(weeklyIntervalDays > 0 && monthlyIntervalDays > weeklyIntervalDays)
        require(weeklyToleranceDays >= 0 && monthlyToleranceDays >= 0)
        require(amountTolerancePercent in MIN_PERCENT..MAX_PERCENT)
        require(priceIncreasePercent in MIN_PRICE_INCREASE_PERCENT..MAX_PERCENT)
        require(allowedSkippedPeriods in 0..MAX_SKIPPED_PERIODS)
    }

    private companion object {
        const val MIN_REQUIRED_OCCURRENCES = 3
        const val MIN_PERCENT = 0
        const val MIN_PRICE_INCREASE_PERCENT = 1
        const val MAX_PERCENT = 100
        const val MAX_SKIPPED_PERIODS = 3
    }
}

data class ExpenseObservation(
    val entryId: UUID,
    val categoryId: UUID,
    val normalizedDescription: String,
    val occurredOn: LocalDate,
    val amount: Money,
) {
    init {
        require(ExpenseDescriptionNormalizer.normalize(normalizedDescription) == normalizedDescription)
        require(amount.currency == Currency.KZT && amount.amount.signum() > 0)
    }
}

data class DetectedRecurringExpense(
    val categoryId: UUID,
    val normalizedDescription: String,
    val frequency: RecurrenceFrequency,
    val scheduleValue: Int,
    val suggestedAmount: Money,
    val evidence: List<ExpenseObservation>,
    val previousTypicalAmount: Money? = null,
) {
    val latestAmount: Money get() = evidence.maxBy(ExpenseObservation::occurredOn).amount
}

data class RulePaymentObservation(
    val ruleId: UUID,
    val occurrenceId: UUID,
    val dueOn: LocalDate,
    val amount: Money,
) {
    init {
        require(amount.currency == Currency.KZT && amount.amount.signum() > 0)
    }
}

data class RecurringPriceIncrease(
    val rule: RecurringRule,
    val previousTypicalAmount: Money,
    val latestAmount: Money,
    val evidence: List<RulePaymentObservation>,
)

enum class RecurringCandidateStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
}

data class RecurringExpenseCandidate(
    val id: UUID,
    val householdId: UUID,
    val memberId: UUID,
    val categoryId: UUID,
    val normalizedDescription: String,
    val frequency: RecurrenceFrequency,
    val scheduleValue: Int,
    val suggestedAmount: Money,
    val status: RecurringCandidateStatus = RecurringCandidateStatus.PENDING,
    val acceptedRuleId: UUID? = null,
    val version: Long = 0,
    val createdAt: Instant,
    val updatedAt: Instant = createdAt,
) {
    init {
        require(ExpenseDescriptionNormalizer.normalize(normalizedDescription) == normalizedDescription)
        suggestedAmount.requirePositive()
        require(suggestedAmount.currency == Currency.KZT)
        require(
            when (frequency) {
                RecurrenceFrequency.MONTHLY -> scheduleValue in FIRST_SCHEDULE_DAY..LAST_MONTH_SCHEDULE_DAY
                RecurrenceFrequency.WEEKLY -> scheduleValue in FIRST_SCHEDULE_DAY..LAST_WEEK_SCHEDULE_DAY
            },
        )
        require((status == RecurringCandidateStatus.ACCEPTED) == (acceptedRuleId != null))
        require(version >= 0 && updatedAt >= createdAt)
    }

    private companion object {
        const val FIRST_SCHEDULE_DAY = 1
        const val LAST_WEEK_SCHEDULE_DAY = 7
        const val LAST_MONTH_SCHEDULE_DAY = 31
    }
}

@Suppress("TooManyFunctions") // The detector keeps all recurrence rules deterministic and colocated.
object RecurringExpenseDetector {
    fun candidates(
        observations: List<ExpenseObservation>,
        activeRules: List<RecurringRule>,
        policy: RecurrenceDetectionPolicy,
    ): List<DetectedRecurringExpense> =
        observations
            .groupBy { it.categoryId to it.normalizedDescription }
            .values
            .mapNotNull { detectGroup(it, policy) }
            .filterNot { detected -> activeRules.any { isDuplicate(it, detected, policy) } }
            .sortedWith(
                compareByDescending<DetectedRecurringExpense> { it.evidence.maxOf(ExpenseObservation::occurredOn) }
                    .thenBy(DetectedRecurringExpense::normalizedDescription),
            )

    fun priceIncreases(
        rules: List<RecurringRule>,
        payments: List<RulePaymentObservation>,
        policy: RecurrenceDetectionPolicy,
    ): List<RecurringPriceIncrease> {
        val active = rules.filter(RecurringRule::active).associateBy(RecurringRule::id)
        return payments
            .groupBy(RulePaymentObservation::ruleId)
            .mapNotNull { (ruleId, values) ->
                val rule = active[ruleId] ?: return@mapNotNull null
                val ordered = values.sortedBy(RulePaymentObservation::dueOn)
                if (ordered.size < policy.minimumOccurrences) return@mapNotNull null
                val recent = ordered.takeLast(policy.minimumOccurrences)
                val previous = median(recent.dropLast(1).map { it.amount.amount })
                val latest = recent.last().amount.amount
                if (!increased(previous, latest, policy.priceIncreasePercent)) return@mapNotNull null
                RecurringPriceIncrease(rule, Money.of(previous), Money.of(latest), recent)
            }.sortedByDescending { it.evidence.last().dueOn }
    }

    private fun detectGroup(
        group: List<ExpenseObservation>,
        policy: RecurrenceDetectionPolicy,
    ): DetectedRecurringExpense? {
        val ordered = group.sortedBy(ExpenseObservation::occurredOn)
        if (ordered.size < policy.minimumOccurrences) return null
        return (0..ordered.size - policy.minimumOccurrences).firstNotNullOfOrNull { start ->
            detectWindow(ordered.subList(start, ordered.size), policy)
        }
    }

    private fun detectWindow(
        evidence: List<ExpenseObservation>,
        policy: RecurrenceDetectionPolicy,
    ): DetectedRecurringExpense? {
        val amounts = evidence.map { it.amount.amount }
        val typical = median(amounts)
        val frequency = frequency(evidence, policy)
        if (amounts.any { !close(typical, it, policy.amountTolerancePercent) } || frequency == null) return null
        val previous = median(amounts.dropLast(1))
        val growth = previous.takeIf { increased(it, amounts.last(), policy.priceIncreasePercent) }
        val selectedEvidence = evidence.takeLast(MAX_EVIDENCE)
        return DetectedRecurringExpense(
            evidence.first().categoryId,
            evidence.first().normalizedDescription,
            frequency,
            schedule(selectedEvidence, frequency),
            Money.of(growth?.let { amounts.last() } ?: typical),
            selectedEvidence,
            growth?.let(Money::of),
        )
    }

    private fun frequency(
        evidence: List<ExpenseObservation>,
        policy: RecurrenceDetectionPolicy,
    ): RecurrenceFrequency? {
        val gaps =
            evidence.zipWithNext {
                first,
                second,
                ->
                ChronoUnit.DAYS.between(first.occurredOn, second.occurredOn)
            }
        return when {
            gaps.all {
                intervalMatches(
                    it,
                    policy.weeklyIntervalDays,
                    policy.weeklyToleranceDays,
                    policy.allowedSkippedPeriods,
                )
            } -> RecurrenceFrequency.WEEKLY

            gaps.all {
                intervalMatches(
                    it,
                    policy.monthlyIntervalDays,
                    policy.monthlyToleranceDays,
                    policy.allowedSkippedPeriods,
                )
            } -> RecurrenceFrequency.MONTHLY

            else -> null
        }
    }

    private fun intervalMatches(
        days: Long,
        targetDays: Int,
        toleranceDays: Int,
        allowedSkippedPeriods: Int,
    ): Boolean {
        val periods = (days.toDouble() / targetDays).roundToInt()
        if (periods !in 1..allowedSkippedPeriods + 1) return false
        return abs(days - targetDays.toLong() * periods) <= toleranceDays.toLong() * periods
    }

    private fun schedule(
        evidence: List<ExpenseObservation>,
        frequency: RecurrenceFrequency,
    ): Int =
        when (frequency) {
            RecurrenceFrequency.WEEKLY -> {
                mode(evidence.map { it.occurredOn.dayOfWeek.value })
            }

            RecurrenceFrequency.MONTHLY -> {
                if (evidence.all { it.occurredOn.dayOfMonth == it.occurredOn.lengthOfMonth() }) {
                    LAST_MONTH_SCHEDULE_DAY
                } else {
                    mode(evidence.map { it.occurredOn.dayOfMonth })
                }
            }
        }

    fun isDuplicate(
        rule: RecurringRule,
        detected: DetectedRecurringExpense,
        policy: RecurrenceDetectionPolicy,
    ): Boolean {
        val identityMatches =
            rule.active &&
                rule.categoryId == detected.categoryId &&
                rule.frequency == detected.frequency &&
                (rule.normalizedDescription == null || rule.normalizedDescription == detected.normalizedDescription)
        val scheduleClose =
            when (rule.frequency) {
                RecurrenceFrequency.WEEKLY -> {
                    rule.scheduleValue == detected.scheduleValue
                }

                RecurrenceFrequency.MONTHLY -> {
                    (rule.scheduleValue >= LAST_WEEK_OF_MONTH && detected.scheduleValue >= LAST_WEEK_OF_MONTH) ||
                        abs(rule.scheduleValue - detected.scheduleValue) <= policy.monthlyToleranceDays
                }
            }
        return identityMatches && scheduleClose &&
            close(rule.amount.amount, detected.suggestedAmount.amount, policy.amountTolerancePercent)
    }

    private fun close(
        expected: BigDecimal,
        actual: BigDecimal,
        tolerancePercent: Int,
    ): Boolean =
        actual
            .subtract(expected)
            .abs()
            .multiply(HUNDRED) <= expected.abs().multiply(BigDecimal(tolerancePercent))

    private fun increased(
        previous: BigDecimal,
        latest: BigDecimal,
        thresholdPercent: Int,
    ): Boolean = latest.subtract(previous).multiply(HUNDRED) >= previous.multiply(BigDecimal(thresholdPercent))

    private fun median(values: List<BigDecimal>): BigDecimal {
        require(values.isNotEmpty())
        val ordered = values.sorted()
        val middle = ordered.size / 2
        return if (ordered.size % 2 == 1) {
            ordered[middle]
        } else {
            ordered[middle - 1].add(ordered[middle]).divide(TWO, MONEY_SCALE, RoundingMode.HALF_UP)
        }
    }

    private fun mode(values: List<Int>): Int =
        values
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenByDescending { it.key })
            .first()
            .key

    private val HUNDRED = BigDecimal("100")
    private val TWO = BigDecimal("2")
    private const val LAST_WEEK_OF_MONTH = 28
    private const val LAST_MONTH_SCHEDULE_DAY = 31
    private const val MONEY_SCALE = 2
    private const val MAX_EVIDENCE = 6
}
