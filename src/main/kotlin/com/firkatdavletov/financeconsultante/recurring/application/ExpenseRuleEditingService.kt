package com.firkatdavletov.financeconsultante.recurring.application

import com.firkatdavletov.financeconsultante.ledger.application.BudgetActor
import com.firkatdavletov.financeconsultante.ledger.application.BudgetRepository
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import com.firkatdavletov.financeconsultante.recurring.domain.RecurringRule
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
@Transactional
class ExpenseRuleEditingService(
    private val plans: RecurringPlanRepository,
    private val budgets: BudgetRepository,
    private val clock: Clock,
) {
    @Suppress("ReturnCount") // Access, stale definitions and invalid details have explicit rejection results.
    fun replaceRuleDetails(
        owner: BudgetActor,
        ruleId: UUID,
        replacement: RecurringRule,
        effectiveOn: LocalDate,
    ): PlanChange<RecurringRuleView> {
        if (!owner.canManageHousehold) return PlanChange.Rejected("Требуется владелец бюджета.")
        if (budgets.findOwnedAccount(owner.householdId, owner.memberId, lock = true) ==
            null
        ) {
            return PlanChange.Rejected("Требуется владелец бюджета.")
        }
        val current =
            plans.findRule(owner.householdId, ruleId, lock = true)
                ?: return PlanChange.Rejected("Правило изменилось или недоступно.")
        if (!current.active ||
            current.version != replacement.version
        ) {
            return PlanChange.Rejected("Правило изменилось или недоступно.")
        }
        val category = budgets.findCategory(owner.householdId, replacement.categoryId)
        if (category == null || category.archived || category.kind != CategoryKind.EXPENSE) {
            return PlanChange.Rejected("Нужна активная категория расходов.")
        }
        if (!validDates(owner, current, replacement, effectiveOn)) {
            return PlanChange.Rejected("Новая дата начала не может быть раньше дня изменения и начала учёта.")
        }
        val updated =
            current.copy(
                categoryId = replacement.categoryId,
                amount = replacement.amount,
                frequency = replacement.frequency,
                scheduleValue = replacement.scheduleValue,
                startsOn = replacement.startsOn,
                ruleName = replacement.ruleName,
                version = current.version + 1,
                updatedAt = clock.instant().truncatedTo(ChronoUnit.MICROS),
                effectiveOn = effectiveOn,
            )
        if (!plans.replaceRuleDetails(
                updated,
                current.version,
            )
        ) {
            return PlanChange.Rejected("Правило изменилось или недоступно.")
        }
        val through = YearMonth.from(maxOf(effectiveOn, updated.startsOn).plusMonths(2)).atEndOfMonth()
        plans.insertOccurrences(updated, updated.dueDatesThrough(through), updated.updatedAt)
        return PlanChange.Applied(checkNotNull(plans.ruleView(owner.householdId, ruleId)))
    }

    private fun validDates(
        owner: BudgetActor,
        current: RecurringRule,
        replacement: RecurringRule,
        effectiveOn: LocalDate,
    ): Boolean {
        val firstDay = owner.startedAt.atZone(ACCOUNTING_ZONE).toLocalDate()
        val newStartBeforeChange = replacement.startsOn != current.startsOn && replacement.startsOn < effectiveOn
        return effectiveOn in firstDay..LocalDate.now(clock.withZone(ACCOUNTING_ZONE)) &&
            replacement.startsOn >= firstDay && !newStartBeforeChange
    }

    companion object {
        private val ACCOUNTING_ZONE = ZoneId.of("Asia/Almaty")
    }
}
