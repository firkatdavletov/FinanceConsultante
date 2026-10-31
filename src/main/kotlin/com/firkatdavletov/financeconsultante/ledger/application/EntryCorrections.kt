package com.firkatdavletov.financeconsultante.ledger.application

import com.firkatdavletov.financeconsultante.access.domain.MemberRole
import com.firkatdavletov.financeconsultante.exchange.application.ExchangeRateJobRepository
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.ledger.domain.EntryChange
import com.firkatdavletov.financeconsultante.ledger.domain.EntryStatus
import com.firkatdavletov.financeconsultante.ledger.domain.ExpenseDescriptionNormalizer
import com.firkatdavletov.financeconsultante.ledger.domain.LedgerEntry
import com.firkatdavletov.financeconsultante.ledger.domain.RefundPolicy
import com.firkatdavletov.financeconsultante.ledger.domain.revised
import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.UUID

sealed interface CorrectionResult {
    data class Updated(
        val entry: LedgerEntry,
    ) : CorrectionResult

    data class Rejected(
        val message: String,
    ) : CorrectionResult
}

data class EntryTarget(
    val householdId: UUID,
    val memberId: UUID,
    val entryId: UUID,
    val version: Long,
    val role: MemberRole = MemberRole.OWNER,
)

@Service
@Transactional
class EntryCorrections(
    private val budgets: BudgetRepository,
    private val ledger: LedgerRepository,
    private val rates: ExchangeRateRepository,
    private val jobs: ExchangeRateJobRepository,
    private val clock: Clock,
) {
    fun selected(target: EntryTarget): LedgerEntry? {
        if (budgets.findOwnedAccount(target.householdId, target.memberId, lock = true) == null) return null
        return ledger.find(target.householdId, target.entryId)?.takeIf {
            it.version == target.version && it.status == EntryStatus.POSTED &&
                (target.role == MemberRole.OWNER || it.actorId == target.memberId)
        }
    }

    // Every guard must finish before a mutation or audit is written.
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun change(
        target: EntryTarget,
        change: EntryChange,
        source: String,
    ): CorrectionResult {
        val original = selected(target) ?: return rejected(STALE)
        if (ledger.refunds(target.householdId, original.id).isNotEmpty()) {
            return rejected("Сначала отмените связанные возвраты через /history; затем исправьте исходный расход.")
        }
        val prepared = changedEntry(original, change)
        if (prepared is CorrectionResult.Rejected) return prepared
        val candidate = (prepared as CorrectionResult.Updated).entry
        if (categoryChangeRequiresUnlink(original, candidate)) {
            return rejected("unlink_before_category_change")
        }
        if (candidate.status == EntryStatus.POSTED && candidate.refundOf != null) {
            val expense = ledger.find(target.householdId, candidate.refundOf) ?: return rejected("Расход недоступен.")
            RefundPolicy.rejection(expense, ledger.refunds(target.householdId, expense.id), candidate)?.let {
                return rejected(it)
            }
        }
        val reason =
            when (change) {
                is EntryChange.Amount -> "AMOUNT_CHANGED"
                is EntryChange.Category -> "CATEGORY_CHANGED"
                is EntryChange.Details -> "DETAILS_CHANGED"
                is EntryChange.Date -> "DATE_CHANGED"
                EntryChange.Void -> "USER_VOID"
            }
        return if (ledger.revise(candidate, target.memberId, reason, source)) {
            CorrectionResult.Updated(candidate)
        } else {
            rejected(STALE)
        }
    }

    @Suppress("ReturnCount") // Validate the proposed fields before constructing a revision.
    private fun changedEntry(original: LedgerEntry, change: EntryChange): CorrectionResult {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        if (change is EntryChange.Details) return changedDetails(original, change, now)
        val date =
            (change as? EntryChange.Date)?.value ?: original.occurredAt.atZone(LedgerOperations.ZONE).toLocalDate()
        val amount = (change as? EntryChange.Amount)?.value ?: original.originalAmount
        if (amount.currency != original.originalAmount.currency) return rejected("Валюта исправления должна совпадать.")
        val categoryId = (change as? EntryChange.Category)?.id ?: original.categoryId
        val category =
            budgets.findCategory(original.householdId, categoryId) ?: return rejected("Категория недоступна.")
        if (category.kind != original.direction.categoryKind ||
            (change is EntryChange.Category && category.archived)
        ) {
            return rejected("Категория недоступна или архивирована.")
        }
        return if (change == EntryChange.Void) {
            CorrectionResult.Updated(original.revised(now).copy(status = EntryStatus.VOIDED))
        } else {
            prepare(original.revised(now), amount, date, categoryId)
        }
    }

    private fun categoryChangeRequiresUnlink(
        original: LedgerEntry,
        candidate: LedgerEntry,
    ): Boolean = candidate.categoryId != original.categoryId && ledger.planLinked(original.householdId, original.id)

    @Suppress("ReturnCount")
    private fun changedDetails(
        original: LedgerEntry,
        change: EntryChange.Details,
        now: Instant,
    ): CorrectionResult {
        if (original.originalAmount.currency != Currency.KZT || change.amount.currency != Currency.KZT ||
            original.direction !in setOf(Direction.EXPENSE, Direction.INCOME)
        ) {
            return rejected("Исправление доступно только для доходов и расходов в KZT с положительной суммой.")
        }
        if (change.amount.amount.signum() <= 0) return rejected("Сумма должна быть положительной.")
        val category =
            budgets.findCategory(original.householdId, change.categoryId)
                ?: return rejected("Категория недоступна.")
        if (category.archived || category.kind != original.direction.categoryKind) {
            return rejected("Категория недоступна или архивирована.")
        }
        val description = change.description.orEmpty().trim()
        if (description.length > ExpenseDescriptionNormalizer.MAX_LENGTH) return rejected("Описание слишком длинное.")
        return CorrectionResult.Updated(
            original.revised(now).copy(
                originalAmount = change.amount,
                amountKzt = change.amount,
                categoryId = change.categoryId,
                normalizedDescription = ExpenseDescriptionNormalizer.normalize(description),
            ),
        )
    }

    @Suppress("ReturnCount")
    fun refund(
        target: EntryTarget,
        amount: Money,
        date: LocalDate,
        source: String,
        id: UUID = UUID.randomUUID(),
    ): CorrectionResult {
        val expense = selected(target) ?: return rejected(STALE)
        return refund(
            expense,
            target.memberId,
            amount,
            date,
            source,
            ledger.refunds(target.householdId, expense.id),
            id,
        )
    }

    @Suppress("ReturnCount") // Guards finish before the expense revision and refund insert are attempted.
    fun refundRemaining(
        target: EntryTarget,
        date: LocalDate,
        source: String,
    ): CorrectionResult {
        val expense = selected(target) ?: return rejected(STALE)
        if (expense.direction != Direction.EXPENSE) return rejected("Возврат возможен только для расхода.")
        val refunds = ledger.refunds(target.householdId, expense.id)
        val refunded = refunds.fold(BigDecimal.ZERO) { sum, refund -> sum + refund.originalAmount.amount }
        val remaining = Money.of(expense.originalAmount.amount - refunded, expense.originalAmount.currency)
        if (remaining.amount.signum() <= 0) return rejected("Расход уже полностью возвращён.")
        return refund(expense, target.memberId, remaining, date, source, refunds)
    }

    @Suppress("ReturnCount") // Every refund invariant must be checked before either ledger row changes.
    private fun refund(
        expense: LedgerEntry,
        memberId: UUID,
        amount: Money,
        date: LocalDate,
        source: String,
        refunds: List<LedgerEntry>,
        id: UUID = UUID.randomUUID(),
    ): CorrectionResult {
        if (expense.direction != Direction.EXPENSE) return rejected("Возврат возможен только для расхода.")
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val template =
            expense.copy(
                id = id,
                direction = Direction.EXPENSE_REFUND,
                actorId = memberId,
                createdAt = now,
                modifiedAt = now,
                sourceKey = source,
                version = 0,
                refundOf = expense.id,
            )
        val prepared = prepare(template, amount, date, expense.categoryId)
        if (prepared is CorrectionResult.Rejected) return prepared
        val candidate = (prepared as CorrectionResult.Updated).entry
        RefundPolicy.rejection(expense, refunds, candidate)?.let {
            return rejected(it)
        }
        // Invalidates old refund commands as well as old correction buttons on the expense.
        if (!ledger.revise(expense.revised(now), memberId, "REFUND_LINKED", source)) return rejected(STALE)
        check(ledger.insert(candidate)) { "Refund financial source conflict" }
        return CorrectionResult.Updated(candidate)
    }

    @Suppress("ReturnCount", "SwallowedException")
    private fun prepare(
        template: LedgerEntry,
        amount: Money,
        date: LocalDate,
        categoryId: UUID,
    ): CorrectionResult {
        val instant = date.atStartOfDay(LedgerOperations.ZONE).toInstant()
        if (instant < template.accountingStartedAt || instant > clock.instant()) {
            return rejected("Дата должна быть между началом учёта и сегодняшним днём.")
        }
        val rate = if (amount.currency == Currency.RUB) rates.find(date) else null
        if (amount.currency == Currency.RUB && rate == null) {
            jobs.enqueueDate(date, RATE_FETCH_TIME, LedgerOperations.ZONE, clock.instant())
            return rejected("Нет курса RUB/KZT за $date. Запрос курса сохранён; повторите команду после загрузки.")
        }
        return try {
            CorrectionResult.Updated(
                template.copy(
                    originalAmount = amount.requirePositive(),
                    amountKzt = rate?.convert(amount) ?: amount,
                    exchangeRate = rate,
                    categoryId = categoryId,
                    occurredAt = instant,
                ),
            )
        } catch (_: IllegalArgumentException) {
            rejected("Сумма или её оценка в KZT вне допустимого диапазона. Баланс не изменён.")
        }
    }

    private fun rejected(message: String) = CorrectionResult.Rejected(message)

    companion object {
        private val RATE_FETCH_TIME = LocalTime.of(7, 0)
        const val STALE = "Действие устарело или запись недоступна. Откройте /history заново."
    }
}
