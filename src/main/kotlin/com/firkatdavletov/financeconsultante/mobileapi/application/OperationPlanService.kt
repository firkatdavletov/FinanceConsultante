package com.firkatdavletov.financeconsultante.mobileapi.application

import com.firkatdavletov.financeconsultante.access.domain.MemberRole
import com.firkatdavletov.financeconsultante.ledger.application.LedgerRepository
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.ledger.domain.EntryStatus
import com.firkatdavletov.financeconsultante.ledger.domain.LedgerEntry
import com.firkatdavletov.financeconsultante.ledger.domain.revised
import com.firkatdavletov.financeconsultante.recurring.application.RecurringPlanRepository
import com.firkatdavletov.financeconsultante.shared.domain.Currency
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

data class PlanLinkTarget(
    val type: String,
    val planId: UUID? = null,
    val ruleId: UUID? = null,
    val dueOn: LocalDate,
)

data class SuggestedPlan(
    val target: PlanLinkTarget,
    val categoryId: UUID,
    val categoryName: String,
    val plannedAmount: String,
    val remainingAmount: String,
    val version: Long,
)

const val PLAN_LINK_INVALID = 422
const val PLAN_LINK_UPGRADE = 426
const val PLAN_LINK_CONFLICT = 409
const val PLAN_HORIZON_YEARS = 5L

class PlanLinkFailure(
    val code: String,
    val status: Int,
) : RuntimeException(code)

interface OperationPlanRepository {
    fun enabled(household: UUID): Boolean

    fun activate(household: UUID)

    fun linked(
        household: UUID,
        ledger: UUID,
    ): PlanLinkTarget?

    fun replace(
        household: UUID,
        ledger: UUID,
        target: PlanLinkTarget?,
    )
}

fun supportsConfirmedPlanLinks(value: String?) = value.orEmpty().split(',', ' ').contains("confirmedPlanLinkingV1")

@Service
class OperationPlanService(
    private val repository: OperationPlanRepository,
    private val plans: RecurringPlanRepository,
    private val ledger: LedgerRepository,
    private val clock: Clock,
    private val budgets: com.firkatdavletov.financeconsultante.ledger.application.BudgetRepository,
) {
    fun requireCapability(
        household: UUID,
        capabilities: String?,
    ) {
        if (repository.enabled(household) &&
            !supportsConfirmedPlanLinks(capabilities)
        ) {
            throw PlanLinkFailure("client_upgrade_required", PLAN_LINK_UPGRADE)
        }
    }

    fun enabled(household: UUID) = repository.enabled(household)

    fun activate(household: UUID) = repository.activate(household)

    fun linked(
        household: UUID,
        id: UUID,
    ) = repository.linked(household, id) != null

    @Suppress("ReturnCount") // Missing or archived operations have no proposal.
    fun candidates(
        actor: CloudBudgetActor,
        operation: UUID,
    ): List<SuggestedPlan> {
        val entry = ledger.find(actor.householdId, operation) ?: return emptyList()
        if (budgets.findCategory(actor.householdId, entry.categoryId)?.archived != false) return emptyList()
        val horizon = LocalDate.now(clock.withZone(ZONE)).plusYears(PLAN_HORIZON_YEARS)
        val result = mutableListOf<SuggestedPlan>()
        if (entry.direction == Direction.INCOME) {
            plans
                .expectedIncomes(actor.householdId)
                .filter {
                    it.expectedIncome.categoryId == entry.categoryId && it.expectedIncome.status.name == "SCHEDULED" &&
                        !it.fulfilled
                }.forEach {
                    val p = it.expectedIncome
                    result +=
                        SuggestedPlan(
                            PlanLinkTarget("INCOME", p.id, dueOn = p.dueOn),
                            p.categoryId,
                            it.categoryName,
                            p.plannedAmount.amount.toPlainString(),
                            it.remainingAmount.amount.toPlainString(),
                            p.version,
                        )
                }
        } else if (entry.direction == Direction.EXPENSE) {
            val existing = plans.occurrences(actor.householdId, horizon)
            existing
                .filter {
                    it.occurrence.categoryId == entry.categoryId && it.occurrence.status.name == "SCHEDULED" &&
                        !it.paidInFull
                }.forEach {
                    val p = it.occurrence
                    result +=
                        SuggestedPlan(
                            PlanLinkTarget("EXPENSE", p.id, p.ruleId, p.dueOn),
                            p.categoryId,
                            it.categoryName,
                            p.plannedAmount.amount.toPlainString(),
                            it.remainingAmount.amount.toPlainString(),
                            p.version,
                        )
                }
            plans.activeRules(actor.householdId).filter { it.categoryId == entry.categoryId }.forEach { rule ->
                val dates = existing.filter { it.occurrence.ruleId == rule.id }.map { it.occurrence.dueOn }.toSet()
                rule.dueDatesThrough(horizon).filter { it !in dates }.forEach { due ->
                    result +=
                        SuggestedPlan(
                            PlanLinkTarget("EXPENSE", ruleId = rule.id, dueOn = due),
                            rule.categoryId,
                            plans.ruleView(actor.householdId, rule.id)!!.categoryName,
                            rule.amount.amount.toPlainString(),
                            rule.amount.amount.toPlainString(),
                            0,
                        )
                }
            }
        }
        return result.sortedWith(compareBy({ it.target.dueOn }, { (it.target.ruleId ?: it.target.planId).toString() }))
    }

    fun change(
        actor: CloudBudgetActor,
        c: CloudBudgetCommand,
        id: UUID,
    ) {
        val entry = ledger.find(actor.householdId, id) ?: fail("invalid_plan_link", PLAN_LINK_INVALID)
        if (actor.role != MemberRole.OWNER && entry.actorId != actor.memberId) throw MobileApiAccessDenied()
        if (entry.version != c.expectedToken) fail("version_conflict")
        val old = repository.linked(actor.householdId, id)
        if (c.type == "REMOVE_OPERATION_PLAN_LINK") {
            if (old == null) return
            repository.replace(actor.householdId, id, null)
        } else {
            val selected = selectPlan(actor, c, entry)
            if (old == selected.target) return
            repository.replace(actor.householdId, id, selected.target)
        }
        check(
            ledger.revise(
                entry.revised(clock.instant().truncatedTo(ChronoUnit.MICROS)),
                actor.memberId,
                "PLAN_LINK_CHANGED",
                "mobile-v4:${c.requestId}",
            ),
        )
    }

    private fun selectPlan(
        actor: CloudBudgetActor,
        c: CloudBudgetCommand,
        entry: LedgerEntry,
    ): SuggestedPlan {
        if (entry.status != EntryStatus.POSTED || entry.originalAmount.currency != Currency.KZT ||
            entry.direction !in setOf(Direction.INCOME, Direction.EXPENSE)
        ) {
            fail("invalid_plan_link", PLAN_LINK_INVALID)
        }
        val contribution =
            entry.amountKzt.amount - ledger.refunds(actor.householdId, entry.id).sumOf { it.amountKzt.amount }
        if (contribution.signum() <= 0) fail("invalid_plan_link", PLAN_LINK_INVALID)
        val target = c.planTarget ?: fail("invalid_plan_link", PLAN_LINK_INVALID)
        if (target.type != entry.direction.name) fail("invalid_plan_link", PLAN_LINK_INVALID)
        if (target.type == "INCOME") validateIncomeTarget(actor, c, entry, target)
        if (target.type == "EXPENSE") prepareExpenseTarget(actor, c, entry, target)
        val selected =
            candidates(actor, entry.id).firstOrNull {
                sameTarget(it.target, target)
            } ?: fail("plan_unavailable")
        if (target.planId != null &&
            selected.target.planId != target.planId
        ) {
            fail("invalid_plan_link", PLAN_LINK_INVALID)
        }
        if (selected.version != c.expectedPlanVersion) fail("plan_changed")
        return selected
    }

    private fun sameTarget(
        a: PlanLinkTarget,
        b: PlanLinkTarget,
    ): Boolean =
        a.type == b.type && a.dueOn == b.dueOn &&
            (if (a.type == "EXPENSE") a.ruleId == b.ruleId else a.planId == b.planId)

    private fun validateIncomeTarget(
        actor: CloudBudgetActor,
        c: CloudBudgetCommand,
        entry: LedgerEntry,
        target: PlanLinkTarget,
    ) {
        if (target.ruleId != null || target.planId == null) fail("invalid_plan_link", PLAN_LINK_INVALID)
        val income =
            plans.findExpectedIncome(actor.householdId, target.planId, lock = true)
                ?: fail("invalid_plan_link", PLAN_LINK_INVALID)
        if (income.categoryId != entry.categoryId) fail("invalid_plan_link", PLAN_LINK_INVALID)
        if (income.version != c.expectedPlanVersion || income.dueOn != target.dueOn) fail("plan_changed")
    }

    private fun prepareExpenseTarget(
        actor: CloudBudgetActor,
        c: CloudBudgetCommand,
        entry: LedgerEntry,
        target: PlanLinkTarget,
    ) {
        val rule =
            target.ruleId?.let { plans.findRule(actor.householdId, it, lock = true) }
                ?: fail("invalid_plan_link", PLAN_LINK_INVALID)
        val occurrence =
            plans.occurrences(actor.householdId, target.dueOn).firstOrNull {
                it.occurrence.ruleId == rule.id && it.occurrence.dueOn == target.dueOn
            }
        if (rule.categoryId != entry.categoryId) fail("invalid_plan_link", PLAN_LINK_INVALID)
        if (occurrence != null && occurrence.occurrence.version != c.expectedPlanVersion) fail("plan_changed")
        if (occurrence == null) {
            if (!rule.active ||
                target.dueOn > LocalDate.now(clock.withZone(ZONE)).plusYears(PLAN_HORIZON_YEARS)
            ) {
                fail("plan_unavailable")
            }
            if (target.dueOn !in
                rule.dueDatesThrough(target.dueOn)
            ) {
                fail("invalid_plan_link", PLAN_LINK_INVALID)
            }
            plans.insertOccurrences(rule, listOf(target.dueOn), clock.instant())
        }
    }

    private fun fail(
        code: String,
        status: Int = PLAN_LINK_CONFLICT,
    ): Nothing = throw PlanLinkFailure(code, status)

    companion object {
        private val ZONE = ZoneId.of("Asia/Almaty")
    }
}
