package com.firkatdavletov.financeconsultante.mobileapi.application

import com.firkatdavletov.financeconsultante.access.application.FamilyBudgetAccess
import com.firkatdavletov.financeconsultante.ledger.application.LedgerInteractionRepository
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.recurring.application.RecurringPlanService
import com.firkatdavletov.financeconsultante.recurring.domain.ExpectedIncomeView
import com.firkatdavletov.financeconsultante.recurring.domain.RecurringOccurrenceView
import com.firkatdavletov.financeconsultante.recurring.domain.RecurringRule
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

enum class PlannedOperationPeriod {
    END_OF_MONTH,
    END_OF_YEAR,
    CUSTOM,
}

class InvalidPlannedOperationRequest(
    message: String,
) : IllegalArgumentException(message)

data class PlannedOperationFilter(
    val type: Direction? = null,
    val period: PlannedOperationPeriod = PlannedOperationPeriod.END_OF_MONTH,
    val dateFrom: LocalDate? = null,
    val dateTo: LocalDate? = null,
) {
    fun dates(asOf: LocalDate): ClosedRange<LocalDate> {
        if (type == Direction.EXPENSE_REFUND) {
            invalid("type must be EXPENSE or INCOME")
        }
        val dates =
            when (period) {
                PlannedOperationPeriod.END_OF_MONTH -> {
                    asOf..YearMonth.from(asOf).atEndOfMonth()
                }

                PlannedOperationPeriod.END_OF_YEAR -> {
                    asOf..asOf.withDayOfYear(asOf.lengthOfYear())
                }

                PlannedOperationPeriod.CUSTOM -> {
                    val from = dateFrom ?: invalid("dateFrom is required for CUSTOM")
                    val to = dateTo ?: invalid("dateTo is required for CUSTOM")
                    from..to
                }
            }
        if (period != PlannedOperationPeriod.CUSTOM && (dateFrom != null || dateTo != null)) {
            invalid("dateFrom and dateTo require period=CUSTOM")
        }
        if (dates.start > dates.endInclusive || dates.start.year < 1) {
            invalid("Dates must be ordered and start in year 1 or later")
        }
        // A request materializes recurring occurrences, so its upper horizon must stay bounded.
        if (dates.endInclusive > asOf.plusYears(MAX_HORIZON_YEARS)) {
            invalid("dateTo must not exceed five years from today")
        }
        return dates
    }

    private fun invalid(message: String): Nothing = throw InvalidPlannedOperationRequest(message)

    companion object {
        private const val MAX_HORIZON_YEARS = 5L
    }
}

sealed interface PlannedOperationItem {
    val id: UUID
    val dueOn: LocalDate

    data class Expense(
        val view: RecurringOccurrenceView,
        val rule: RecurringRule,
    ) : PlannedOperationItem {
        override val id: UUID get() = view.occurrence.id
        override val dueOn: LocalDate get() = view.occurrence.dueOn
    }

    data class Income(
        val view: ExpectedIncomeView,
    ) : PlannedOperationItem {
        override val id: UUID get() = view.expectedIncome.id
        override val dueOn: LocalDate get() = view.expectedIncome.dueOn
    }
}

data class PlannedOperationList(
    val asOf: LocalDate,
    val dateFrom: LocalDate,
    val dateTo: LocalDate,
    val items: List<PlannedOperationItem>,
)

@Service
class PlannedOperationService(
    private val access: FamilyBudgetAccess,
    private val ledger: LedgerInteractionRepository,
    private val recurring: RecurringPlanService,
    private val clock: Clock,
) {
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    fun plannedOperations(
        telegramUserId: Long,
        filter: PlannedOperationFilter,
    ): PlannedOperationList {
        val member = access.activeMember(telegramUserId) ?: throw MobileApiAccessDenied()
        val owner =
            ledger
                .owned(telegramUserId)
                ?.takeIf { it.householdId == member.householdId && it.memberId == member.id }
                ?: throw MobileApiAccessDenied()
        val asOf = LocalDate.now(clock.withZone(API_ZONE))
        val dates = filter.dates(asOf)
        val expenses = if (filter.type != Direction.INCOME) expenses(owner, asOf, dates) else emptyList()
        val incomes =
            if (filter.type != Direction.EXPENSE) {
                recurring
                    .expectedIncomeForecast(owner, dates.endInclusive)
                    .filter { it.expectedIncome.dueOn in dates }
                    .map { PlannedOperationItem.Income(it) }
            } else {
                emptyList()
            }
        val items = (expenses + incomes).sortedWith(compareBy({ it.dueOn }, { it.id.toString() }))
        return PlannedOperationList(asOf, dates.start, dates.endInclusive, items)
    }

    private fun expenses(
        owner: OwnedLedger,
        asOf: LocalDate,
        dates: ClosedRange<LocalDate>,
    ): List<PlannedOperationItem.Expense> {
        val rules = mutableMapOf<UUID, RecurringRule>()
        return recurring
            .plannedExpenses(owner, asOf, dates.endInclusive)
            .expenses
            .filter { it.occurrence.dueOn in dates }
            .map { view ->
                val ruleId = view.occurrence.ruleId
                val rule = rules.getOrPut(ruleId) { checkNotNull(recurring.rule(owner, ruleId)) }
                PlannedOperationItem.Expense(view, rule)
            }
    }

    companion object {
        private val API_ZONE = ZoneId.of("Asia/Almaty")
    }
}
