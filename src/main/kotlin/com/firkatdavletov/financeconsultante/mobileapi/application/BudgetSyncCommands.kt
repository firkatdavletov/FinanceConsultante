package com.firkatdavletov.financeconsultante.mobileapi.application

import com.firkatdavletov.financeconsultante.access.application.FamilyBudgetAccess
import com.firkatdavletov.financeconsultante.access.domain.MemberRole
import com.firkatdavletov.financeconsultante.ledger.application.BudgetRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class BudgetSyncCommand(
    val requestId: UUID,
    val householdId: UUID,
    val memberId: UUID,
    val type: String,
    val entityId: UUID,
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
)

data class BudgetCommandReceipt(
    val requestId: UUID,
    val revision: Long,
    val entityToken: Long?,
)

data class StoredBudgetCommand(
    val hash: String,
    val receipt: BudgetCommandReceipt,
)

interface BudgetCommandRepository {
    fun receipt(
        householdId: UUID,
        memberId: UUID,
        requestId: UUID,
    ): StoredBudgetCommand?

    fun token(
        householdId: UUID,
        kind: String,
        entityId: UUID,
        lock: Boolean = false,
    ): Long?

    fun remember(
        command: BudgetSyncCommand,
        hash: String,
        receipt: BudgetCommandReceipt,
    )
}

@Service
class BudgetSyncCommands(
    private val access: FamilyBudgetAccess,
    private val budgets: BudgetRepository,
    private val commands: BudgetCommandRepository,
    private val sync: OfflineSyncRepository,
    private val categories: CategoryMutationService,
    private val expenses: PlannedExpenseMutationService,
    private val incomes: PlannedIncomeMutationService,
    private val clock: Clock,
) {
    @Transactional
    fun execute(
        user: Long,
        command: BudgetSyncCommand,
    ): BudgetCommandReceipt {
        val member = access.activeMember(user) ?: denied()
        if (command.householdId != member.householdId || command.memberId != member.id) {
            conflict("scope_changed")
        }
        if (member.role != MemberRole.OWNER) throw MobileApiOwnerRequired()
        val account =
            budgets.findOwnedAccount(member.householdId, member.id, lock = true)
                ?: denied()
        val hash = hash(command)
        commands.receipt(member.householdId, member.id, command.requestId)?.let {
            if (it.hash != hash) conflict("idempotency_conflict")
            return it.receipt
        }
        val today = LocalDate.now(clock.withZone(ZONE))
        if (command.authoredOn > today || command.authoredOn < account.accountingStartedAt.atZone(ZONE).toLocalDate()) {
            invalidDate()
        }
        val kind = kind(command.type)
        val current = commands.token(member.householdId, kind, command.entityId, lock = true)
        validateToken(command, current)
        apply(user, command)
        val receipt =
            BudgetCommandReceipt(
                command.requestId,
                sync.revision(member.householdId),
                commands.token(member.householdId, kind, command.entityId),
            )
        commands.remember(command, hash, receipt)
        return receipt
    }

    private fun validateToken(
        command: BudgetSyncCommand,
        current: Long?,
    ) {
        if (command.type.startsWith("CREATE_")) {
            if (current != null) conflict("entity_exists")
        } else if (current == null || command.expectedToken == null || command.expectedToken != current) {
            conflict("entity_changed")
        }
    }

    private fun kind(type: String): String =
        when (type) {
            "CREATE_CATEGORY", "UPDATE_CATEGORY", "DELETE_CATEGORY" -> "CATEGORY"
            "CREATE_RULE", "UPDATE_RULE", "DELETE_RULE" -> "RECURRING_RULE"
            "CREATE_INCOME", "UPDATE_INCOME", "DELETE_INCOME" -> "EXPECTED_INCOME"
            "CLOSE_EXPENSE" -> "RECURRING_OCCURRENCE"
            else -> throw InvalidOfflineRequest("Unknown command")
        }

    private fun denied(): Nothing = throw MobileApiAccessDenied()

    private fun conflict(code: String): Nothing = throw OfflineSyncConflict(code)

    private fun invalidDate(): Nothing = throw InvalidOfflineRequest("Invalid authored date", "invalid_date")

    private fun apply(
        user: Long,
        c: BudgetSyncCommand,
    ) {
        when (c.type) {
            "CREATE_CATEGORY" -> {
                categories.create(user, c.name, c.kind, c.icon, c.entityId)
            }

            "UPDATE_CATEGORY" -> {
                categories.update(user, c.entityId, c.name, c.icon)
            }

            "DELETE_CATEGORY" -> {
                categories.delete(user, c.entityId)
            }

            "CREATE_RULE" -> {
                expenses.create(
                    user,
                    c.categoryId,
                    c.amount,
                    c.frequency,
                    c.scheduleValue,
                    c.startsOn,
                    c.entityId,
                )
            }

            "UPDATE_RULE" -> {
                expenses.update(user, c.entityId, c.expectedToken, c.amount, c.authoredOn)
            }

            "DELETE_RULE" -> {
                expenses.delete(user, c.entityId, c.expectedToken, c.authoredOn)
            }

            "CLOSE_EXPENSE" -> {
                expenses.close(user, c.entityId, c.expectedToken)
            }

            "CREATE_INCOME" -> {
                incomes.create(user, c.categoryId, c.amount, c.dueOn, c.authoredOn, c.entityId)
            }

            "UPDATE_INCOME" -> {
                incomes.update(
                    user,
                    c.entityId,
                    c.expectedToken,
                    c.categoryId,
                    c.amount,
                    c.dueOn,
                    c.authoredOn,
                )
            }

            "DELETE_INCOME" -> {
                incomes.delete(user, c.entityId, c.expectedToken)
            }
        }
    }

    private fun hash(c: BudgetSyncCommand): String {
        val fields =
            listOf(
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
            )
        val bytes =
            fields
                .joinToString("") { value ->
                    if (value == null) "-1:" else "${value.toString().length}:$value"
                }.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val ZONE = ZoneId.of("Asia/Almaty")
    }
}
