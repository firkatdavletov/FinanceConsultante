package com.firkatdavletov.financeconsultante.mobileapi.application

import com.firkatdavletov.financeconsultante.access.application.FamilyBudgetAccess
import com.firkatdavletov.financeconsultante.ledger.application.LedgerInteractionRepository
import com.firkatdavletov.financeconsultante.recurring.application.RecurringPlanService
import com.firkatdavletov.financeconsultante.recurring.domain.RecurringOccurrenceView
import com.firkatdavletov.financeconsultante.recurring.domain.RecurringRule
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

data class PlannedExpenseList(
    val asOf: LocalDate,
    val throughInclusive: LocalDate,
    val items: List<PlannedExpenseItem>,
)

data class PlannedExpenseItem(
    val expense: RecurringOccurrenceView,
    val rule: RecurringRule,
)

@Service
class PlannedExpenseService(
    private val access: FamilyBudgetAccess,
    private val ledger: LedgerInteractionRepository,
    private val recurring: RecurringPlanService,
    private val clock: Clock,
) {
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    fun plannedExpenses(telegramUserId: Long): PlannedExpenseList {
        val owner =
            access.activeMember(telegramUserId)?.let { member ->
                ledger
                    .owned(telegramUserId)
                    ?.takeIf { it.householdId == member.householdId && it.memberId == member.id }
            } ?: throw MobileApiAccessDenied()

        val asOf = LocalDate.now(clock.withZone(API_ZONE))
        val schedule = recurring.plannedExpenses(owner, asOf)
        val rules = mutableMapOf<java.util.UUID, RecurringRule>()
        val items =
            schedule.expenses.map { expense ->
                val ruleId = expense.occurrence.ruleId
                val rule = rules.getOrPut(ruleId) { checkNotNull(recurring.rule(owner, ruleId)) }
                PlannedExpenseItem(expense, rule)
            }
        return PlannedExpenseList(schedule.asOf, schedule.throughInclusive, items)
    }

    companion object {
        private val API_ZONE = ZoneId.of("Asia/Almaty")
    }
}
