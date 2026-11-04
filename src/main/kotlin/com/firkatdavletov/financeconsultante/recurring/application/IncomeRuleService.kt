package com.firkatdavletov.financeconsultante.recurring.application

import com.firkatdavletov.financeconsultante.ledger.application.BudgetActor
import com.firkatdavletov.financeconsultante.ledger.application.BudgetRepository
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import com.firkatdavletov.financeconsultante.recurring.domain.ExpectedIncomeStatus
import com.firkatdavletov.financeconsultante.recurring.domain.IncomeFrequency
import com.firkatdavletov.financeconsultante.recurring.domain.IncomeRule
import com.firkatdavletov.financeconsultante.recurring.domain.planName
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

data class IncomeRuleDetails(
    val categoryId: UUID?,
    val amount: Money?,
    val dueOn: LocalDate?,
    val name: String?,
    val frequency: String?,
    val active: Boolean?,
)

@Service
@Transactional
class IncomeRuleService(
    private val rules: IncomeRuleRepository,
    private val plans: RecurringPlanRepository,
    private val budgets: BudgetRepository,
    private val clock: Clock,
) {
    @Suppress("CyclomaticComplexMethod") // Nullable legacy metadata and optional patches share one atomic conversion.
    fun save(
        owner: BudgetActor,
        id: UUID,
        details: IncomeRuleDetails,
        effectiveOn: LocalDate,
    ) {
        require(owner.canManageHousehold)
        requireNotNull(budgets.findOwnedAccount(owner.householdId, owner.memberId, lock = true))
        val current = rules.rules(owner.householdId).firstOrNull { it.id == id }
        val legacy = if (current == null) plans.findExpectedIncome(owner.householdId, id, lock = true) else null
        require(legacy?.ruleId == null)
        val dueOn = requireNotNull(details.dueOn ?: current?.dueOn ?: legacy?.dueOn)
        val firstDay = owner.startedAt.atZone(ZONE).toLocalDate()
        require(effectiveOn in firstDay..LocalDate.now(clock.withZone(ZONE)))
        require(dueOn >= firstDay && (dueOn == (current?.dueOn ?: legacy?.dueOn) || dueOn >= effectiveOn))
        val categoryId = requireNotNull(details.categoryId ?: current?.categoryId ?: legacy?.categoryId)
        val category = requireNotNull(budgets.findCategory(owner.householdId, categoryId))
        require(!category.archived && category.kind == CategoryKind.INCOME)
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val rule =
            IncomeRule(
                id,
                owner.householdId,
                current?.memberId ?: legacy?.memberId ?: owner.memberId,
                categoryId,
                requireNotNull(details.amount ?: current?.amount ?: legacy?.plannedAmount),
                dueOn,
                details.name?.trim() ?: current?.incomeName ?: legacy?.incomeName ?: category.name,
                details.frequency?.let(IncomeFrequency::valueOf) ?: current?.incomeFrequency ?: legacy?.incomeFrequency
                    ?: IncomeFrequency.ONCE,
                details.active ?: current?.incomeActive ?: legacy?.incomeActive ?: true,
                current?.version?.plus(1) ?: legacy?.version?.plus(1) ?: 0,
                current?.createdAt ?: legacy?.createdAt ?: now,
                now,
                effectiveOn,
            )
        rules.save(rule)
        if (legacy != null) rules.attach(owner.householdId, id)
        rules.reconcile(
            rule,
            maxOf(effectiveOn, dueOn)
                .plusMonths(2)
                .withDayOfMonth(1)
                .plusMonths(1)
                .minusDays(1),
        )
    }

    fun delete(
        owner: BudgetActor,
        id: UUID,
        effectiveOn: LocalDate,
    ) {
        require(owner.canManageHousehold)
        requireNotNull(budgets.findOwnedAccount(owner.householdId, owner.memberId, lock = true))
        val current = rules.rules(owner.householdId).firstOrNull { it.id == id } ?: return
        val updated =
            current.copy(
                status = ExpectedIncomeStatus.CANCELLED,
                incomeActive = false,
                effectiveOn = effectiveOn,
                version = current.version + 1,
                updatedAt = clock.instant().truncatedTo(ChronoUnit.MICROS),
            )
        rules.save(updated)
        rules.reconcile(updated, effectiveOn)
    }

    fun metadata(
        owner: BudgetActor,
        id: UUID,
        details: IncomeRuleDetails,
    ) {
        require(owner.canManageHousehold)
        requireNotNull(budgets.findOwnedAccount(owner.householdId, owner.memberId, lock = true))
        details.name?.let(::planName)
        details.frequency?.let { require(IncomeFrequency.valueOf(it) == IncomeFrequency.ONCE) }
        if (details.name != null || details.frequency != null || details.active != null) {
            rules.metadata(owner.householdId, id, details.name?.trim(), details.frequency, details.active)
        }
    }

    companion object {
        private val ZONE = ZoneId.of("Asia/Almaty")
    }
}
