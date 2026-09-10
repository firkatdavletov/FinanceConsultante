package com.firkatdavletov.financeconsultante.recurring.application

import com.firkatdavletov.financeconsultante.ledger.application.AccountingService
import com.firkatdavletov.financeconsultante.ledger.application.BudgetRepository
import com.firkatdavletov.financeconsultante.ledger.application.LedgerRepository
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.ledger.application.RecordEntry
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.ledger.domain.EntryStatus
import com.firkatdavletov.financeconsultante.recurring.domain.ExpectedIncome
import com.firkatdavletov.financeconsultante.recurring.domain.ExpectedIncomeStatus
import com.firkatdavletov.financeconsultante.recurring.domain.ExpectedIncomeView
import com.firkatdavletov.financeconsultante.recurring.domain.OccurrenceStatus
import com.firkatdavletov.financeconsultante.recurring.domain.RecurrenceFrequency
import com.firkatdavletov.financeconsultante.recurring.domain.RecurringOccurrenceView
import com.firkatdavletov.financeconsultante.recurring.domain.RecurringRule
import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
@Transactional
@Suppress("TooManyFunctions", "ReturnCount", "ComplexCondition") // Guards stop stale or invalid financial mutations.
class RecurringPlanService(
    private val plans: RecurringPlanRepository,
    private val budgets: BudgetRepository,
    private val ledger: LedgerRepository,
    private val accounting: AccountingService,
    private val clock: Clock,
) {
    fun createRule(
        owner: OwnedLedger,
        frequency: RecurrenceFrequency,
        scheduleValue: Int,
        amount: Money,
        categoryId: UUID,
        startsOn: LocalDate,
    ): PlanChange<RecurringRuleView> {
        category(owner, categoryId, CategoryKind.EXPENSE)?.let { return PlanChange.Rejected(it) }
        if (startsOn < owner.startedAt.atZone(ACCOUNTING_ZONE).toLocalDate()) {
            return PlanChange.Rejected(
                "Правило не может начинаться раньше даты начала учёта.",
            )
        }
        val now = now()
        val rule =
            RecurringRule(
                UUID.randomUUID(),
                owner.householdId,
                owner.memberId,
                categoryId,
                frequency,
                scheduleValue,
                amount,
                startsOn,
                createdAt = now,
            )
        plans.insertRule(rule)
        generate(rule, horizon(startsOn), now)
        return PlanChange.Applied(ruleView(owner.householdId, rule.id))
    }

    fun overview(
        owner: OwnedLedger,
        asOf: LocalDate,
    ): RecurringOverview {
        val through = horizon(asOf)
        generate(owner.householdId, through)
        return RecurringOverview(
            plans.rules(owner.householdId, LIST_LIMIT),
            plans.occurrences(owner.householdId, through, LIST_LIMIT),
        )
    }

    fun updateRuleAmount(
        owner: OwnedLedger,
        ruleId: UUID,
        version: Long,
        amount: Money,
        effectiveOn: LocalDate,
    ): PlanChange<RecurringRuleView> {
        val current =
            plans.findRule(owner.householdId, ruleId, lock = true)
                ?: return PlanChange.Rejected(STALE_RULE)
        if (current.version != version || !current.active) return PlanChange.Rejected(STALE_RULE)
        val updated = current.copy(amount = amount, version = version + 1, updatedAt = now())
        if (!plans.updateRuleAmount(updated, version, effectiveOn)) return PlanChange.Rejected(STALE_RULE)
        generate(updated, horizon(effectiveOn), updated.updatedAt)
        return PlanChange.Applied(ruleView(owner.householdId, ruleId))
    }

    fun deactivateRule(
        owner: OwnedLedger,
        ruleId: UUID,
        version: Long,
        effectiveOn: LocalDate,
    ): PlanChange<RecurringRuleView> {
        val current =
            plans.findRule(owner.householdId, ruleId, lock = true)
                ?: return PlanChange.Rejected(STALE_RULE)
        if (current.version != version || !current.active) return PlanChange.Rejected(STALE_RULE)
        val updated = current.copy(active = false, version = version + 1, updatedAt = now())
        if (!plans.deactivateRule(updated, version, effectiveOn)) return PlanChange.Rejected(STALE_RULE)
        return PlanChange.Applied(ruleView(owner.householdId, ruleId))
    }

    fun cancelOccurrence(
        owner: OwnedLedger,
        occurrenceId: UUID,
        version: Long,
        today: LocalDate,
    ): PlanChange<RecurringOccurrenceView> {
        val occurrence =
            plans.findOccurrence(owner.householdId, occurrenceId, lock = true)
                ?: return PlanChange.Rejected(STALE_OCCURRENCE)
        if (occurrence.version != version || occurrence.status != OccurrenceStatus.SCHEDULED) {
            return PlanChange.Rejected(STALE_OCCURRENCE)
        }
        if (occurrence.dueOn < today) {
            return PlanChange.Rejected(
                "Просроченное обязательство нельзя отменить " +
                    "как будущее.",
            )
        }
        if (!plans.cancelOccurrence(occurrenceId, version, now())) return PlanChange.Rejected(STALE_OCCURRENCE)
        return PlanChange.Applied(checkNotNull(plans.occurrenceView(owner.householdId, occurrenceId)))
    }

    @Suppress("LongParameterList") // The command is one atomic payment and ledger link.
    fun pay(
        owner: OwnedLedger,
        occurrenceId: UUID,
        version: Long,
        amount: Money,
        paidOn: LocalDate,
        source: String,
    ): PlanChange<PaymentReceipt> {
        val occurrence =
            plans.findOccurrence(owner.householdId, occurrenceId, lock = true)
                ?: return PlanChange.Rejected(STALE_OCCURRENCE)
        val before =
            plans.occurrenceView(owner.householdId, occurrenceId) ?: return PlanChange.Rejected(STALE_OCCURRENCE)
        if (before.paidInFull) {
            return PlanChange.Rejected(
                "Обязательство уже оплачено. Повторной проводки нет.",
            )
        }
        if (occurrence.version != version || occurrence.status != OccurrenceStatus.SCHEDULED) {
            return PlanChange.Rejected(STALE_OCCURRENCE)
        }
        dateForFact(owner, paidOn)?.let { return PlanChange.Rejected(it) }
        category(owner, occurrence.categoryId, CategoryKind.EXPENSE)?.let { return PlanChange.Rejected(it) }
        val recorded =
            accounting.record(
                RecordEntry(
                    owner.householdId,
                    owner.memberId,
                    occurrence.categoryId,
                    Direction.EXPENSE,
                    amount,
                    paidOn.atStartOfDay(ACCOUNTING_ZONE).toInstant(),
                    source,
                ),
            )
        plans.linkPayment(owner.householdId, occurrenceId, recorded.entry.id, now())
        check(plans.advanceOccurrence(occurrenceId, version, now())) { "Occurrence changed while locked" }
        val after = checkNotNull(plans.occurrenceView(owner.householdId, occurrenceId))
        return PlanChange.Applied(PaymentReceipt(after, recorded.entry, recorded.recorded))
    }

    fun remainingObligations(
        owner: OwnedLedger,
        throughInclusive: LocalDate,
    ): Money {
        generate(owner.householdId, throughInclusive)
        val total =
            plans
                .occurrences(owner.householdId, throughInclusive)
                .fold(BigDecimal.ZERO) { sum, occurrence -> sum + occurrence.remainingAmount.amount }
        return Money.of(total, Currency.KZT)
    }

    fun createExpectedIncome(
        owner: OwnedLedger,
        amount: Money,
        dueOn: LocalDate,
        categoryId: UUID,
        today: LocalDate,
    ): PlanChange<ExpectedIncomeView> {
        category(owner, categoryId, CategoryKind.INCOME)?.let { return PlanChange.Rejected(it) }
        if (dueOn < today) {
            return PlanChange.Rejected(
                "Ожидаемый доход должен быть сегодня или в будущем.",
            )
        }
        val now = now()
        val expected =
            ExpectedIncome(
                UUID.randomUUID(),
                owner.householdId,
                owner.memberId,
                categoryId,
                amount,
                dueOn,
                createdAt = now,
            )
        plans.insertExpectedIncome(expected)
        return PlanChange.Applied(expectedView(owner.householdId, expected.id))
    }

    fun expectedIncomes(owner: OwnedLedger): List<ExpectedIncomeView> =
        plans.expectedIncomes(owner.householdId, limit = LIST_LIMIT)

    fun expectedIncomeForecast(
        owner: OwnedLedger,
        throughInclusive: LocalDate,
    ): List<ExpectedIncomeView> =
        plans
            .expectedIncomes(owner.householdId, throughInclusive)
            .filter { it.forecastAmount.amount.signum() > 0 }

    fun cancelExpectedIncome(
        owner: OwnedLedger,
        expectedIncomeId: UUID,
        version: Long,
        today: LocalDate,
    ): PlanChange<ExpectedIncomeView> {
        val expected =
            plans.findExpectedIncome(owner.householdId, expectedIncomeId, lock = true)
                ?: return PlanChange.Rejected(STALE_EXPECTED)
        val view =
            plans.expectedIncomeView(owner.householdId, expectedIncomeId)
                ?: return PlanChange.Rejected(STALE_EXPECTED)
        if (expected.version != version || expected.status != ExpectedIncomeStatus.SCHEDULED || view.fulfilled) {
            return PlanChange.Rejected(STALE_EXPECTED)
        }
        if (expected.dueOn < today) {
            return PlanChange.Rejected(
                "Просроченный ожидаемый доход нельзя отменить " +
                    "как будущий.",
            )
        }
        if (!plans.cancelExpectedIncome(expectedIncomeId, version, now())) return PlanChange.Rejected(STALE_EXPECTED)
        return PlanChange.Applied(expectedView(owner.householdId, expectedIncomeId))
    }

    @Suppress("LongParameterList") // The command is one atomic income and plan link.
    fun receiveExpectedIncome(
        owner: OwnedLedger,
        expectedIncomeId: UUID,
        version: Long,
        amount: Money,
        receivedOn: LocalDate,
        source: String,
    ): PlanChange<IncomeReceipt> {
        val expected =
            plans.findExpectedIncome(owner.householdId, expectedIncomeId, lock = true)
                ?: return PlanChange.Rejected(STALE_EXPECTED)
        val before = expectedView(owner.householdId, expectedIncomeId)
        if (before.fulfilled) {
            return PlanChange.Rejected(
                "Ожидаемый доход уже получен. Повторной проводки нет.",
            )
        }
        if (expected.version != version || expected.status != ExpectedIncomeStatus.SCHEDULED) {
            return PlanChange.Rejected(STALE_EXPECTED)
        }
        dateForFact(owner, receivedOn)?.let { return PlanChange.Rejected(it) }
        category(owner, expected.categoryId, CategoryKind.INCOME)?.let { return PlanChange.Rejected(it) }
        val recorded =
            accounting.record(
                RecordEntry(
                    owner.householdId,
                    owner.memberId,
                    expected.categoryId,
                    Direction.INCOME,
                    amount,
                    receivedOn.atStartOfDay(ACCOUNTING_ZONE).toInstant(),
                    source,
                ),
            )
        plans.linkExpectedIncome(owner.householdId, expectedIncomeId, recorded.entry.id, now())
        check(plans.advanceExpectedIncome(expectedIncomeId, version, now())) { "Expected income changed while locked" }
        val after = expectedView(owner.householdId, expectedIncomeId)
        return PlanChange.Applied(IncomeReceipt(after, recorded.entry, recorded.recorded))
    }

    fun matchExpectedIncome(
        owner: OwnedLedger,
        expectedIncomeId: UUID,
        version: Long,
        ledgerEntryId: UUID,
    ): PlanChange<ExpectedIncomeView> {
        val expected =
            plans.findExpectedIncome(owner.householdId, expectedIncomeId, lock = true)
                ?: return PlanChange.Rejected(STALE_EXPECTED)
        val before = expectedView(owner.householdId, expectedIncomeId)
        if (before.fulfilled) {
            return PlanChange.Rejected(
                "Ожидаемый доход уже сопоставлен с проводкой.",
            )
        }
        if (expected.version != version || expected.status != ExpectedIncomeStatus.SCHEDULED) {
            return PlanChange.Rejected(STALE_EXPECTED)
        }
        val entry =
            ledger.find(owner.householdId, ledgerEntryId)
                ?: return PlanChange.Rejected("Проводка дохода недоступна.")
        if (
            entry.status != EntryStatus.POSTED || entry.direction != Direction.INCOME ||
            entry.categoryId != expected.categoryId || entry.originalAmount.currency != Currency.KZT
        ) {
            return PlanChange.Rejected(
                "Нужна проведённая запись дохода KZT той же категории.",
            )
        }
        if (plans.ledgerEntryAlreadyPlanned(owner.householdId, ledgerEntryId)) {
            return PlanChange.Rejected("Эта проводка уже связана с другим планом.")
        }
        plans.linkExpectedIncome(owner.householdId, expectedIncomeId, ledgerEntryId, now())
        check(plans.advanceExpectedIncome(expectedIncomeId, version, now())) { "Expected income changed while locked" }
        return PlanChange.Applied(expectedView(owner.householdId, expectedIncomeId))
    }

    fun generateAll() {
        val today = LocalDate.now(clock.withZone(ACCOUNTING_ZONE))
        val through = horizon(today)
        plans.activeRules().forEach { generate(it, through, now()) }
    }

    private fun generate(
        householdId: UUID,
        throughInclusive: LocalDate,
    ) {
        plans.activeRules(householdId).forEach { generate(it, throughInclusive, now()) }
    }

    private fun generate(
        rule: RecurringRule,
        throughInclusive: LocalDate,
        now: Instant,
    ) {
        plans.insertOccurrences(rule, rule.dueDatesThrough(throughInclusive), now)
    }

    private fun ruleView(
        householdId: UUID,
        ruleId: UUID,
    ): RecurringRuleView = checkNotNull(plans.rules(householdId, LIST_LIMIT).firstOrNull { it.rule.id == ruleId })

    private fun expectedView(
        householdId: UUID,
        expectedIncomeId: UUID,
    ): ExpectedIncomeView = checkNotNull(plans.expectedIncomeView(householdId, expectedIncomeId))

    private fun category(
        owner: OwnedLedger,
        categoryId: UUID,
        kind: CategoryKind,
    ): String? {
        val category =
            budgets.findCategory(owner.householdId, categoryId)
                ?: return "Категория недоступна. Выберите ID из /categories."
        return if (category.archived || category.kind != kind) {
            "Нужна активная категория типа " +
                if (kind == CategoryKind.EXPENSE) "расход." else "доход."
        } else {
            null
        }
    }

    private fun dateForFact(
        owner: OwnedLedger,
        date: LocalDate,
    ): String? {
        val today = LocalDate.now(clock.withZone(ACCOUNTING_ZONE))
        return when {
            date < owner.startedAt.atZone(ACCOUNTING_ZONE).toLocalDate() -> {
                "Дата раньше начала учёта."
            }

            date > today -> {
                "Будущая дата не является фактом оплаты или дохода."
            }

            else -> {
                null
            }
        }
    }

    private fun horizon(from: LocalDate): LocalDate = YearMonth.from(from.plusMonths(HORIZON_MONTHS)).atEndOfMonth()

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    companion object {
        private val ACCOUNTING_ZONE = java.time.ZoneId.of("Asia/Almaty")
        private const val HORIZON_MONTHS = 2L
        private const val LIST_LIMIT = 12
        private const val STALE_RULE =
            "Правило изменилось или недоступно. Откройте /recurring."
        private const val STALE_OCCURRENCE =
            "Обязательство изменилось или недоступно. Откройте /recurring."
        private const val STALE_EXPECTED =
            "План дохода изменился или недоступен. Откройте /expected."
    }
}
