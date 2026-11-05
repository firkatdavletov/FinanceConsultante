package com.firkatdavletov.financeconsultante.mobileapi.application

import com.firkatdavletov.financeconsultante.access.domain.MemberRole
import com.firkatdavletov.financeconsultante.ledger.application.BudgetActor
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// The v4 denomination labels the budget's exact accounting values.
internal val BUDGET_CURRENCIES = setOf("KZT", "RUB", "USD", "EUR")

data class CloudBudget(
    val scope: SyncScope,
    val capabilities: List<String> = listOf("confirmedPlanLinkingV1", "incomeRuleEditingV1"),
)

data class CloudSeedCategory(
    val id: UUID,
    val name: String,
    val kind: String,
    val icon: String,
    val dictionaryKey: String,
)

data class CreateCloudBudget(
    val requestId: UUID,
    val householdId: UUID,
    val memberId: UUID,
    val openingBalance: String,
    val accountingStartedOn: LocalDate,
    val categories: List<CloudSeedCategory>,
    val currency: String? = null,
)

data class CloudBudgetActor(
    override val householdId: UUID,
    override val memberId: UUID,
    override val startedAt: Instant,
    override val role: MemberRole,
    val createRequestId: UUID? = null,
    val createPayloadHash: String? = null,
    val currency: String = "KZT",
    val currencyVersion: Long = 0,
) : BudgetActor {
    fun scope() =
        SyncScope(householdId, memberId, role.name, startedAt, currency = currency, currencyVersion = currencyVersion)
}

data class CloudBudgetCommand(
    val requestId: UUID,
    val householdId: UUID,
    val memberId: UUID,
    val type: String,
    val entityId: String,
    val authoredOn: LocalDate,
    val expectedToken: Long? = null,
    val categoryId: UUID? = null,
    val name: String? = null,
    val kind: String? = null,
    val icon: String? = null,
    val amount: String? = null,
    val frequency: String? = null,
    val scheduleValue: Int? = null,
    val startsOn: LocalDate? = null,
    val dueOn: LocalDate? = null,
    val ruleId: UUID? = null,
    val occurredOn: LocalDate? = null,
    val description: String? = null,
    val refundId: UUID? = null,
    val replaceDetails: Boolean? = false,
    val currency: String? = null,
    val planMatchingMode: String? = null,
    val planTarget: PlanLinkTarget? = null,
    val expectedPlanVersion: Long? = null,
    val ruleName: String? = null,
    val incomeName: String? = null,
    val incomeFrequency: String? = null,
    val incomeActive: Boolean? = null,
)

interface CloudBudgetRepository {
    fun lock(tokenHash: String)

    fun binding(tokenHash: String): CloudBudgetActor?

    fun importLegacy(tokenHash: String)

    fun legacyUserId(tokenHash: String): Long?

    fun create(
        tokenHash: String,
        request: CreateCloudBudget,
        payloadHash: String,
    )

    fun restoreRule(
        householdId: UUID,
        id: UUID,
        effectiveOn: LocalDate,
    )

    fun restoreOperation(
        householdId: UUID,
        memberId: UUID,
        id: UUID,
        source: String,
        at: Instant,
    )

    fun restoreIncome(
        householdId: UUID,
        id: UUID,
    )

    fun occurrenceId(
        householdId: UUID,
        ruleId: UUID,
        dueOn: LocalDate,
    ): UUID?
}

class CloudBudgetFailure(
    val code: String,
) : RuntimeException(code)
