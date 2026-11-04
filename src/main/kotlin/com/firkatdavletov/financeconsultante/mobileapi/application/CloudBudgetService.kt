package com.firkatdavletov.financeconsultante.mobileapi.application

import com.firkatdavletov.financeconsultante.access.domain.MemberRole
import com.firkatdavletov.financeconsultante.ledger.application.AccountingService
import com.firkatdavletov.financeconsultante.ledger.application.BudgetRepository
import com.firkatdavletov.financeconsultante.ledger.application.CategoryManagement
import com.firkatdavletov.financeconsultante.ledger.application.CategoryRepository
import com.firkatdavletov.financeconsultante.ledger.application.CorrectionResult
import com.firkatdavletov.financeconsultante.ledger.application.EntryCorrections
import com.firkatdavletov.financeconsultante.ledger.application.EntryTarget
import com.firkatdavletov.financeconsultante.ledger.application.LedgerRepository
import com.firkatdavletov.financeconsultante.ledger.application.RecordEntry
import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.ledger.domain.EntryChange
import com.firkatdavletov.financeconsultante.ledger.domain.ExpenseDescriptionNormalizer
import com.firkatdavletov.financeconsultante.recurring.application.PlanChange
import com.firkatdavletov.financeconsultante.recurring.application.RecurringPlanService
import com.firkatdavletov.financeconsultante.recurring.domain.RecurrenceFrequency
import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Base64
import java.util.UUID

/** v4 financial processing uses the resolved budget actor, independently of its login provider. */
@Service
@Suppress("LongParameterList", "TooManyFunctions", "ThrowsCount")
class CloudBudgetService(
    private val repository: CloudBudgetRepository,
    private val budgets: BudgetRepository,
    private val categories: CategoryRepository,
    private val categoryManagement: CategoryManagement,
    private val recurring: RecurringPlanService,
    private val accounting: AccountingService,
    private val ledger: LedgerRepository,
    private val corrections: EntryCorrections,
    private val sync: OfflineSyncRepository,
    private val commands: BudgetCommandRepository,
    private val clock: Clock,
    private val bootstrapValidator: CloudBudgetBootstrapValidator,
    private val operationPlans: OperationPlanService,
) {
    @Transactional
    fun legacyUserId(tokenHash: String): Long? {
        repository.importLegacy(tokenHash)
        return repository.legacyUserId(tokenHash)
    }

    @Transactional
    fun find(tokenHash: String): CloudBudget {
        repository.importLegacy(tokenHash)
        return CloudBudget(actor(tokenHash).scope())
    }

    @Transactional
    fun create(
        tokenHash: String,
        request: CreateCloudBudget,
    ): CloudBudget {
        repository.lock(tokenHash)
        repository.importLegacy(tokenHash)
        val fingerprint =
            hash(
                listOf(
                    request.requestId,
                    request.householdId,
                    request.memberId,
                    request.openingBalance,
                    request.accountingStartedOn,
                    request.currency,
                ) +
                    request.categories.flatMap { listOf(it.id, it.name, it.kind, it.icon, it.dictionaryKey) },
            )
        repository.binding(tokenHash)?.let {
            if (it.createRequestId == request.requestId &&
                it.createPayloadHash == fingerprint
            ) {
                return CloudBudget(it.scope())
            }
            throw CloudBudgetFailure(
                if (it.createRequestId ==
                    request.requestId
                ) {
                    "idempotency_conflict"
                } else {
                    "budget_already_exists"
                },
            )
        }
        bootstrapValidator.validate(request)
        repository.create(tokenHash, request, fingerprint)
        return CloudBudget(actor(tokenHash).scope())
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    fun changes(
        tokenHash: String,
        cursor: String?,
        limit: Int,
    ): SyncPage {
        repository.importLegacy(tokenHash)
        return changes(actor(tokenHash), cursor, limit)
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    fun changes(
        actor: CloudBudgetActor,
        cursor: String?,
        limit: Int,
        capabilities: String? = null,
    ): SyncPage {
        requireNotNull(budgets.findOwnedAccount(actor.householdId, actor.memberId, lock = true))
        operationPlans.requireCapability(actor.householdId, capabilities)
        require(limit in 1..MAX_PAGE_SIZE)
        categoryManagement.ensureFallback(actor)
        recurring.ensureOccurrences(actor, YearMonth.from(today().plusMonths(2)).atEndOfMonth())
        val position = position(cursor, actor)
        val revision = sync.revision(actor.householdId)
        val upper = position.second.takeUnless { it == 0L } ?: revision
        require(position.first <= upper && upper <= revision)
        val rows = sync.dashboardChanges(actor.householdId, actor.memberId, position.first, upper, limit + 1)
        val page = rows.take(limit)
        val more = rows.size > limit
        val after = if (more) page.last().revision else upper
        val next = "v4:${actor.householdId}:${actor.memberId}:$after:${if (more) upper else 0}"
        return SyncPage(
            actor.scope(),
            page,
            Base64.getUrlEncoder().withoutPadding().encodeToString(next.toByteArray(Charsets.UTF_8)),
            more,
            upper,
        )
    }

    @Transactional
    fun command(
        tokenHash: String,
        c: CloudBudgetCommand,
    ): BudgetCommandReceipt {
        repository.importLegacy(tokenHash)
        return command(actor(tokenHash), c)
    }

    @Transactional
    fun command(
        actor: CloudBudgetActor,
        c: CloudBudgetCommand,
        capabilities: String? = null,
    ): BudgetCommandReceipt {
        if (c.householdId != actor.householdId ||
            c.memberId != actor.memberId
        ) {
            throw CloudBudgetFailure("scope_changed")
        }
        if (actor.role != MemberRole.OWNER &&
            c.type !in
            setOf(
                "CREATE_OPERATION",
                "UPDATE_OPERATION",
                "REFUND_OPERATION",
                "SET_OPERATION_PLAN_LINK",
                "REMOVE_OPERATION_PLAN_LINK",
            )
        ) {
            throw MobileApiAccessDenied()
        }
        requireNotNull(budgets.findOwnedAccount(actor.householdId, actor.memberId, lock = true))
        val fingerprint = fingerprint(c)
        commands.receipt(actor.householdId, actor.memberId, c.requestId)?.let {
            if (it.hash != fingerprint) throw CloudBudgetFailure("idempotency_conflict")
            return it.receipt
        }
        val confirmed = validatePlanContract(actor, c, capabilities)
        require(c.replaceDetails != true || c.type == "UPDATE_OPERATION")
        require(c.currency == null || c.currency == "KZT")
        require(c.authoredOn in actor.startedAt.atZone(ZONE).toLocalDate()..today())
        val id =
            if (c.type == "CLOSE_EXPENSE" && c.ruleId != null && c.dueOn != null) {
                recurring.ensureOccurrences(actor, c.dueOn)
                repository.occurrenceId(actor.householdId, c.ruleId, c.dueOn) ?: invalid("Occurrence unavailable")
            } else {
                UUID.fromString(c.entityId)
            }
        apply(actor, c, id)
        if (confirmed) operationPlans.activate(actor.householdId)
        return rememberReceipt(actor, c, id, fingerprint)
    }

    private fun fingerprint(c: CloudBudgetCommand): String =
        hash(
            listOf(
                c.requestId,
                c.householdId,
                c.memberId,
                c.type,
                c.entityId,
                c.authoredOn,
                c.expectedToken,
                c.categoryId,
                c.name,
                c.kind,
                c.icon,
                c.amount,
                c.frequency,
                c.scheduleValue,
                c.startsOn,
                c.dueOn,
                c.ruleId,
                c.occurredOn,
                c.description,
                c.refundId,
                c.currency,
            ) + (if (c.replaceDetails == true) listOf("replaceDetails", true) else emptyList()) +
                (if (c.planMatchingMode != null) listOf("planMatchingMode", c.planMatchingMode) else emptyList()) +
                (
                    if (c.planTarget != null ||
                        c.expectedPlanVersion != null
                    ) {
                        listOf(
                            "planTarget",
                            c.planTarget?.type,
                            c.planTarget?.planId,
                            c.planTarget?.ruleId,
                            c.planTarget?.dueOn,
                            c.expectedPlanVersion,
                        )
                    } else {
                        emptyList()
                    }
                ),
        )

    private fun validatePlanContract(
        actor: CloudBudgetActor,
        c: CloudBudgetCommand,
        capabilities: String?,
    ): Boolean {
        operationPlans.requireCapability(actor.householdId, capabilities)
        val confirmed =
            c.planMatchingMode == "CONFIRM_REQUIRED" ||
                c.type in setOf("SET_OPERATION_PLAN_LINK", "REMOVE_OPERATION_PLAN_LINK")
        if (confirmed &&
            !supportsConfirmedPlanLinks(capabilities)
        ) {
            throw PlanLinkFailure("client_upgrade_required", PLAN_LINK_UPGRADE)
        }
        if (c.planMatchingMode != null &&
            (c.type != "CREATE_OPERATION" || c.planMatchingMode != "CONFIRM_REQUIRED")
        ) {
            throw PlanLinkFailure("invalid_plan_link", PLAN_LINK_INVALID)
        }
        if (c.type == "CREATE_OPERATION" && supportsConfirmedPlanLinks(capabilities) &&
            c.planMatchingMode != "CONFIRM_REQUIRED"
        ) {
            throw PlanLinkFailure("invalid_plan_link", PLAN_LINK_INVALID)
        }
        return confirmed
    }

    private fun rememberReceipt(
        actor: CloudBudgetActor,
        c: CloudBudgetCommand,
        id: UUID,
        fingerprint: String,
    ): BudgetCommandReceipt {
        val receipt =
            BudgetCommandReceipt(
                c.requestId,
                sync.revision(actor.householdId),
                commands.token(actor.householdId, kind(c.type), id) ?: 0,
                if (c.type == "CREATE_OPERATION" &&
                    c.planMatchingMode == "CONFIRM_REQUIRED"
                ) {
                    operationPlans.candidates(actor, id).firstOrNull()
                } else {
                    null
                },
            )
        commands.remember(
            BudgetSyncCommand(c.requestId, c.householdId, c.memberId, c.type, id, c.authoredOn),
            fingerprint,
            receipt,
        )
        return receipt
    }

    private fun apply(
        actor: CloudBudgetActor,
        c: CloudBudgetCommand,
        id: UUID,
    ) {
        val source = "mobile-v4:${c.requestId}"
        when (c.type) {
            "CREATE_CATEGORY", "UPDATE_CATEGORY", "DELETE_CATEGORY" -> category(actor, c, id, source)
            "CREATE_RULE", "UPDATE_RULE" -> updateRule(actor, c, id)
            "DELETE_RULE" -> deleteRule(actor, c, id)
            "CREATE_INCOME", "UPDATE_INCOME" -> updateIncome(actor, c, id)
            "DELETE_INCOME" -> deleteIncome(actor, id)
            "CLOSE_EXPENSE" -> closeExpense(actor, id)
            "SET_OPERATION_PLAN_LINK", "REMOVE_OPERATION_PLAN_LINK" -> operationPlans.change(actor, c, id)
            "CREATE_OPERATION" -> createOperation(actor, c, id, source)
            "UPDATE_OPERATION", "REFUND_OPERATION" -> mutateOperation(actor, c, id, source)
            else -> invalid("Unsupported command")
        }
    }

    private fun deleteRule(
        actor: CloudBudgetActor,
        c: CloudBudgetCommand,
        id: UUID,
    ) {
        recurring.rule(actor, id)?.takeIf { it.active }?.let {
            applied(recurring.deactivateRule(actor, id, it.version, c.authoredOn))
        }
    }

    private fun deleteIncome(
        actor: CloudBudgetActor,
        id: UUID,
    ) {
        recurring.expectedIncome(actor, id)?.takeIf { it.expectedIncome.status.name != "CANCELLED" }?.let {
            applied(recurring.cancelExpectedIncome(actor, id, it.expectedIncome.version))
        }
    }

    private fun closeExpense(
        actor: CloudBudgetActor,
        id: UUID,
    ) {
        val occurrence = recurring.occurrence(actor, id) ?: invalid("Occurrence unavailable")
        if (occurrence.occurrence.status.name !=
            "PAID"
        ) {
            applied(recurring.closeOccurrence(actor, id, occurrence.occurrence.version))
        }
    }

    private fun createOperation(
        actor: CloudBudgetActor,
        c: CloudBudgetCommand,
        id: UUID,
        source: String,
    ) {
        require(c.description.orEmpty().length <= ExpenseDescriptionNormalizer.MAX_LENGTH)
        val direction = Direction.valueOf(requireNotNull(c.kind))
        require(direction != Direction.EXPENSE_REFUND)
        val result =
            accounting.record(
                RecordEntry(
                    actor.householdId,
                    actor.memberId,
                    requireNotNull(c.categoryId),
                    direction,
                    amount(c.amount),
                    requireNotNull(c.occurredOn).atStartOfDay(ZONE).toInstant(),
                    source,
                    ExpenseDescriptionNormalizer.normalize(c.description.orEmpty()),
                    id = id,
                    confirmPlanLink =
                        c.planMatchingMode == "CONFIRM_REQUIRED" || operationPlans.enabled(actor.householdId),
                ),
            )
        sync.remember(actor.householdId, actor.memberId, c.requestId, hash(listOf(c.requestId)), result.entry.id)
    }

    private fun mutateOperation(
        actor: CloudBudgetActor,
        c: CloudBudgetCommand,
        id: UUID,
        source: String,
    ) {
        var current = ledger.find(actor.householdId, id)
        if (current == null && c.type == "UPDATE_OPERATION") {
            createOperation(actor, c.copy(kind = c.kind ?: "EXPENSE"), id, source)
            return
        }
        current = current ?: invalid("Operation unavailable")
        val allowedDirections =
            if (c.type == "UPDATE_OPERATION") setOf(Direction.EXPENSE, Direction.INCOME) else setOf(Direction.EXPENSE)
        require(current.originalAmount.currency == Currency.KZT && current.direction in allowedDirections)
        if (current.status == com.firkatdavletov.financeconsultante.ledger.domain.EntryStatus.VOIDED) {
            repository.restoreOperation(actor.householdId, actor.memberId, id, "$source:restore", clock.instant())
            current = requireNotNull(ledger.find(actor.householdId, id))
        }
        if (categoryChangeRequiresUnlink(actor, c, current)) {
            throw PlanLinkFailure("unlink_before_category_change", PLAN_LINK_INVALID)
        }
        val target = EntryTarget(actor.householdId, actor.memberId, id, current.version, actor.role)
        val result =
            if (c.type ==
                "UPDATE_OPERATION"
            ) {
                val change =
                    if (c.replaceDetails == true) {
                        EntryChange.Details(amount(c.amount), requireNotNull(c.categoryId), c.description)
                    } else {
                        EntryChange.Amount(amount(c.amount))
                    }
                corrections.change(target, change, source)
            } else {
                corrections.refund(target, amount(c.amount), c.authoredOn, source, requireNotNull(c.refundId))
            }
        if (result is CorrectionResult.Rejected) invalid(result.message)
        if (c.type ==
            "REFUND_OPERATION"
        ) {
            sync.remember(
                actor.householdId,
                actor.memberId,
                c.requestId,
                hash(listOf(c.requestId)),
                (result as CorrectionResult.Updated).entry.id,
            )
        }
    }

    private fun categoryChangeRequiresUnlink(
        actor: CloudBudgetActor,
        c: CloudBudgetCommand,
        entry: com.firkatdavletov.financeconsultante.ledger.domain.LedgerEntry,
    ): Boolean =
        c.type == "UPDATE_OPERATION" && c.replaceDetails == true && c.categoryId != entry.categoryId &&
            operationPlans.linked(actor.householdId, entry.id)

    private fun category(
        actor: CloudBudgetActor,
        c: CloudBudgetCommand,
        id: UUID,
        source: String,
    ) {
        val current = budgets.findCategory(actor.householdId, id)
        if (c.type == "DELETE_CATEGORY") {
            if (current != null && !current.archived) requireNotNull(categoryManagement.delete(actor, id, source))
            return
        }
        val name = c.name ?: current?.name ?: invalid("Name required")
        val icon = c.icon ?: current?.icon ?: invalid("Icon required")
        val kind = c.kind?.let(CategoryKind::valueOf) ?: current?.kind ?: invalid("Kind required")
        require(icon.isNotBlank())
        require(categoryManagement.validName(name))
        require(categories.categories(actor.householdId).none { it.id != id && it.name.equals(name, true) })
        if (current == null) {
            require(categories.categories(actor.householdId).size < MAX_CATEGORIES)
            require(categories.create(Category(id, actor.householdId, name, kind, icon = icon)))
        } else {
            require(kind == current.kind)
            require(current.dictionaryKey != "OTHER" || current.name == name)
            if (current.archived) require(categories.archive(actor.householdId, id, false))
            require(categories.rename(actor.householdId, id, name))
            require(categories.changeIcon(actor.householdId, id, icon))
        }
    }

    private fun updateRule(
        actor: CloudBudgetActor,
        c: CloudBudgetCommand,
        id: UUID,
    ) {
        repository.restoreRule(actor.householdId, id, c.authoredOn)
        val current = recurring.rule(actor, id)
        if (current == null) {
            applied(
                recurring.createRule(
                    actor,
                    RecurrenceFrequency.valueOf(requireNotNull(c.frequency)),
                    c.scheduleValue,
                    amount(c.amount),
                    requireNotNull(c.categoryId),
                    requireNotNull(c.startsOn),
                    id = id,
                    authoredOn = c.authoredOn,
                ),
            )
            return
        }
        applied(recurring.updateRuleAmount(actor, id, current.version, amount(c.amount), c.authoredOn))
    }

    private fun updateIncome(
        actor: CloudBudgetActor,
        c: CloudBudgetCommand,
        id: UUID,
    ) {
        repository.restoreIncome(actor.householdId, id)
        val current = recurring.expectedIncome(actor, id)?.expectedIncome
        if (current == null) {
            applied(
                recurring.createExpectedIncome(
                    actor,
                    amount(c.amount),
                    requireNotNull(c.dueOn),
                    requireNotNull(c.categoryId),
                    c.authoredOn,
                    id,
                ),
            )
            return
        }
        applied(
            recurring.updateExpectedIncome(
                actor,
                id,
                current.version,
                c.amount?.let(::amount) ?: current.plannedAmount,
                c.categoryId ?: current.categoryId,
                c.dueOn ?: current.dueOn,
                c.authoredOn,
            ),
        )
    }

    private fun <T> applied(result: PlanChange<T>): T =
        when (result) {
            is PlanChange.Applied -> result.value
            is PlanChange.Rejected -> invalid(result.message)
        }

    private fun actor(tokenHash: String) = repository.binding(tokenHash) ?: throw CloudBudgetFailure("budget_not_found")

    private fun amount(value: String?): Money = Money.of(requireNotNull(value)).requirePositive()

    private fun today() = LocalDate.now(clock.withZone(ZONE))

    private fun invalid(message: String): Nothing = throw InvalidOfflineRequest(message)

    private fun kind(type: String) =
        when {
            type.endsWith("CATEGORY") -> "CATEGORY"
            type.endsWith("RULE") -> "RECURRING_RULE"
            type.endsWith("INCOME") -> "EXPECTED_INCOME"
            type == "CLOSE_EXPENSE" -> "RECURRING_OCCURRENCE"
            else -> "LEDGER"
        }

    private fun position(
        cursor: String?,
        actor: CloudBudgetActor,
    ): Pair<Long, Long> {
        if (cursor == null) return 0L to 0L
        require(cursor.length <= MAX_CURSOR_LENGTH)
        val parts = String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8).split(':')
        require(parts.size == CURSOR_PARTS && parts[0] == "v4")
        if (parts[1] != actor.householdId.toString() ||
            parts[2] != actor.memberId.toString()
        ) {
            throw CloudBudgetFailure("scope_changed")
        }
        return (parts[CURSOR_AFTER].toLong().also { require(it >= 0) }) to
            (parts[CURSOR_UPPER].toLong().also { require(it >= 0) })
    }

    private fun hash(values: List<Any?>): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(
                values
                    .joinToString("") {
                        if (it ==
                            null
                        ) {
                            "-1:"
                        } else {
                            "${it.toString().length}:$it"
                        }
                    }.toByteArray(Charsets.UTF_8),
            ).joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_PAGE_SIZE = 200
        private const val MAX_CATEGORIES = 100
        private const val MAX_CURSOR_LENGTH = 512
        private const val CURSOR_PARTS = 5
        private const val CURSOR_AFTER = 3
        private const val CURSOR_UPPER = 4
        private val ZONE = ZoneId.of("Asia/Almaty")
    }
}
