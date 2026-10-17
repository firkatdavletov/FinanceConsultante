package com.firkatdavletov.financeconsultante.mobileapi.application

import com.firkatdavletov.financeconsultante.access.application.FamilyBudgetAccess
import com.firkatdavletov.financeconsultante.access.domain.MemberRole
import com.firkatdavletov.financeconsultante.ledger.application.LedgerInteractionRepository
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.recurring.application.PlanChange
import com.firkatdavletov.financeconsultante.recurring.application.RecurringPlanService
import com.firkatdavletov.financeconsultante.recurring.domain.ExpectedIncomeStatus
import com.firkatdavletov.financeconsultante.recurring.domain.ExpectedIncomeView
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class MobileApiPlannedIncomeOwnerRequired :
    IllegalStateException("Planned income management requires OWNER membership")

class InvalidPlannedIncomeRequest(
    message: String,
) : IllegalArgumentException(message)

class MobileApiPlannedIncomeNotFound : NoSuchElementException("Planned income is unavailable")

class MobileApiPlannedIncomeConflict(
    message: String,
) : IllegalStateException(message)

data class MutatedPlannedIncome(
    val asOf: LocalDate,
    val item: ExpectedIncomeView,
)

@Service
@Suppress("ThrowsCount") // Access, validation, lookup, and optimistic conflicts have distinct HTTP outcomes.
class PlannedIncomeMutationService(
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
        dueOn: LocalDate?,
    ): MutatedPlannedIncome {
        val owner = requireOwner(telegramUserId)
        val today = today()
        val result =
            recurring.createExpectedIncome(
                owner,
                parseAmount(amount),
                dueOn ?: invalid("dueOn is required"),
                categoryId ?: invalid("categoryId is required"),
                today,
            )
        return when (result) {
            is PlanChange.Applied -> MutatedPlannedIncome(today, result.value)
            is PlanChange.Rejected -> invalid(result.message)
        }
    }

    @Transactional
    fun update(
        telegramUserId: Long,
        expectedIncomeId: UUID,
        version: Long?,
        categoryId: UUID?,
        amount: String?,
        dueOn: LocalDate?,
    ): MutatedPlannedIncome {
        val owner = requireOwner(telegramUserId)
        val expectedVersion = parseVersion(version)
        if (categoryId == null && amount == null && dueOn == null) {
            invalid("At least one of categoryId, amount, or dueOn is required")
        }
        val current = recurring.expectedIncome(owner, expectedIncomeId) ?: notFound()
        if (
            current.expectedIncome.version != expectedVersion ||
            current.expectedIncome.status != ExpectedIncomeStatus.SCHEDULED ||
            current.fulfilled
        ) {
            conflict()
        }
        val today = today()
        val result =
            recurring.updateExpectedIncome(
                owner,
                expectedIncomeId,
                expectedVersion,
                amount?.let(::parseAmount) ?: current.expectedIncome.plannedAmount,
                categoryId ?: current.expectedIncome.categoryId,
                dueOn ?: current.expectedIncome.dueOn,
                today,
            )
        return when (result) {
            is PlanChange.Applied -> MutatedPlannedIncome(today, result.value)
            is PlanChange.Rejected -> rejectedUpdate(owner, expectedIncomeId, expectedVersion, result.message)
        }
    }

    @Transactional
    fun delete(
        telegramUserId: Long,
        expectedIncomeId: UUID,
        version: Long?,
    ) {
        val owner = requireOwner(telegramUserId)
        val expectedVersion = parseVersion(version)
        val current = recurring.expectedIncome(owner, expectedIncomeId) ?: notFound()
        if (
            current.expectedIncome.version != expectedVersion ||
            current.expectedIncome.status != ExpectedIncomeStatus.SCHEDULED ||
            current.fulfilled
        ) {
            conflict()
        }
        when (recurring.cancelExpectedIncome(owner, expectedIncomeId, expectedVersion)) {
            is PlanChange.Applied -> Unit
            is PlanChange.Rejected -> conflict()
        }
    }

    private fun rejectedUpdate(
        owner: OwnedLedger,
        expectedIncomeId: UUID,
        expectedVersion: Long,
        message: String,
    ): Nothing {
        val latest = recurring.expectedIncome(owner, expectedIncomeId) ?: notFound()
        if (
            latest.expectedIncome.version != expectedVersion ||
            latest.expectedIncome.status != ExpectedIncomeStatus.SCHEDULED ||
            latest.fulfilled
        ) {
            conflict()
        }
        invalid(message)
    }

    private fun requireOwner(telegramUserId: Long): OwnedLedger {
        val member = access.activeMember(telegramUserId) ?: throw MobileApiAccessDenied()
        if (member.role != MemberRole.OWNER) throw MobileApiPlannedIncomeOwnerRequired()
        return ledger
            .owned(telegramUserId)
            ?.takeIf { it.householdId == member.householdId && it.memberId == member.id }
            ?: throw MobileApiAccessDenied()
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

    private fun today(): LocalDate = LocalDate.now(clock.withZone(API_ZONE))

    private fun invalid(message: String): Nothing = throw InvalidPlannedIncomeRequest(message)

    private fun notFound(): Nothing = throw MobileApiPlannedIncomeNotFound()

    private fun conflict(): Nothing = throw MobileApiPlannedIncomeConflict("Planned income changed or is fulfilled")

    companion object {
        private val API_ZONE = ZoneId.of("Asia/Almaty")
        private val MONEY_PATTERN = Regex("[0-9]+(?:\\.[0-9]{1,2})?")
    }
}
