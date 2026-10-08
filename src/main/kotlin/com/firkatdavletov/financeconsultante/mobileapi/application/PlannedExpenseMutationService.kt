package com.firkatdavletov.financeconsultante.mobileapi.application

import com.firkatdavletov.financeconsultante.access.application.FamilyBudgetAccess
import com.firkatdavletov.financeconsultante.access.domain.MemberRole
import com.firkatdavletov.financeconsultante.ledger.application.LedgerInteractionRepository
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.recurring.application.PlanChange
import com.firkatdavletov.financeconsultante.recurring.application.RecurringPlanService
import com.firkatdavletov.financeconsultante.recurring.application.RecurringRuleView
import com.firkatdavletov.financeconsultante.recurring.domain.OccurrenceStatus
import com.firkatdavletov.financeconsultante.recurring.domain.RecurrenceFrequency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class MobileApiPlannedExpenseOwnerRequired :
    IllegalStateException("Planned expense management requires OWNER membership")

class InvalidPlannedExpenseRequest(
    message: String,
) : IllegalArgumentException(message)

class MobileApiPlannedExpenseNotFound : NoSuchElementException("Planned expense is unavailable")

class MobileApiPlannedExpenseConflict(
    message: String,
) : IllegalStateException(message)

data class ClosedPlannedExpense(
    val asOf: LocalDate,
    val item: PlannedExpenseItem,
)

@Service
@Suppress("TooManyFunctions", "ThrowsCount") // Guards keep validation and access failures explicit at the API boundary.
class PlannedExpenseMutationService(
    private val access: FamilyBudgetAccess,
    private val ledger: LedgerInteractionRepository,
    private val recurring: RecurringPlanService,
    private val clock: Clock,
) {
    @Transactional
    fun create(
        telegramUserId: Long,
        categoryId: UUID?,
        amount: String?,
        frequency: String?,
        scheduleValue: Int?,
        startsOn: LocalDate?,
    ): RecurringRuleView {
        val owner = requireOwner(telegramUserId)
        val recurrenceFrequency = parseFrequency(frequency)
        val result =
            recurring.createRule(
                owner,
                recurrenceFrequency,
                parseScheduleValue(recurrenceFrequency, scheduleValue),
                parseAmount(amount),
                categoryId ?: invalid("categoryId is required"),
                startsOn ?: invalid("startsOn is required"),
            )
        return result.appliedOrInvalid()
    }

    @Transactional
    fun update(
        telegramUserId: Long,
        ruleId: UUID,
        version: Long?,
        amount: String?,
    ): RecurringRuleView {
        val owner = requireOwner(telegramUserId)
        val expectedVersion = parseVersion(version)
        val current = recurring.rule(owner, ruleId) ?: notFound()
        if (current.version != expectedVersion || !current.active) conflict()
        return when (
            val result =
                recurring.updateRuleAmount(
                    owner,
                    ruleId,
                    expectedVersion,
                    parseAmount(amount),
                    today(),
                )
        ) {
            is PlanChange.Applied -> result.value
            is PlanChange.Rejected -> conflict()
        }
    }

    @Transactional
    fun delete(
        telegramUserId: Long,
        ruleId: UUID,
        version: Long?,
    ) {
        val owner = requireOwner(telegramUserId)
        val expectedVersion = parseVersion(version)
        val current = recurring.rule(owner, ruleId) ?: notFound()
        if (current.version != expectedVersion || !current.active) conflict()
        when (val result = recurring.deactivateRule(owner, ruleId, expectedVersion, today())) {
            is PlanChange.Applied -> Unit
            is PlanChange.Rejected -> conflict()
        }
    }

    @Transactional
    fun close(
        telegramUserId: Long,
        occurrenceId: UUID,
        version: Long?,
    ): ClosedPlannedExpense {
        val owner = requireOwner(telegramUserId)
        val expectedVersion = parseVersion(version)
        val current = recurring.occurrence(owner, occurrenceId) ?: notFound()
        if (current.occurrence.version != expectedVersion || current.occurrence.status != OccurrenceStatus.SCHEDULED) {
            conflict()
        }
        if (current.paidAmount.amount.signum() == 0) {
            conflict("Planned expense must have at least one posted payment before it can be closed")
        }
        val closed =
            when (val result = recurring.closeOccurrence(owner, occurrenceId, expectedVersion)) {
                is PlanChange.Applied -> result.value
                is PlanChange.Rejected -> conflict("Planned expense or its payment changed")
            }
        val rule = recurring.rule(owner, closed.occurrence.ruleId) ?: notFound()
        return ClosedPlannedExpense(today(), PlannedExpenseItem(closed, rule))
    }

    private fun requireOwner(telegramUserId: Long): OwnedLedger {
        val member = access.activeMember(telegramUserId) ?: throw MobileApiAccessDenied()
        if (member.role != MemberRole.OWNER) throw MobileApiPlannedExpenseOwnerRequired()
        return ledger
            .owned(telegramUserId)
            ?.takeIf { it.householdId == member.householdId && it.memberId == member.id }
            ?: throw MobileApiAccessDenied()
    }

    private fun parseFrequency(value: String?): RecurrenceFrequency =
        value
            ?.let { runCatching { RecurrenceFrequency.valueOf(it) }.getOrNull() }
            ?: invalid("frequency must be MONTHLY or WEEKLY")

    private fun parseScheduleValue(
        frequency: RecurrenceFrequency,
        value: Int?,
    ): Int {
        val scheduleValue = value ?: invalid("scheduleValue is required")
        val valid =
            when (frequency) {
                RecurrenceFrequency.MONTHLY -> scheduleValue in 1..31
                RecurrenceFrequency.WEEKLY -> scheduleValue in 1..7
            }
        if (!valid) invalid("scheduleValue is outside the frequency range")
        return scheduleValue
    }

    private fun parseAmount(value: String?): Money {
        val amount = value ?: invalid("amount is required")
        if (!MONEY_PATTERN.matches(amount)) {
            invalid("amount must be a positive KZT decimal with at most two digits")
        }
        return runCatching { Money.of(amount).requirePositive() }
            .getOrElse { invalid("amount must be a positive KZT decimal within the supported range") }
    }

    private fun parseVersion(value: Long?): Long {
        val version = value ?: invalid("version is required")
        if (version < 0) invalid("version must be non-negative")
        return version
    }

    private fun PlanChange<RecurringRuleView>.appliedOrInvalid(): RecurringRuleView =
        when (this) {
            is PlanChange.Applied -> value
            is PlanChange.Rejected -> invalid(message)
        }

    private fun today(): LocalDate = LocalDate.now(clock.withZone(API_ZONE))

    private fun invalid(message: String): Nothing = throw InvalidPlannedExpenseRequest(message)

    private fun notFound(): Nothing = throw MobileApiPlannedExpenseNotFound()

    private fun conflict(message: String = "Planned expense changed or is inactive"): Nothing =
        throw MobileApiPlannedExpenseConflict(message)

    companion object {
        private val API_ZONE = ZoneId.of("Asia/Almaty")
        private val MONEY_PATTERN = Regex("[0-9]+(?:\\.[0-9]{1,2})?")
    }
}
