package com.firkatdavletov.financeconsultante.mobileapi.application

import com.firkatdavletov.financeconsultante.access.application.FamilyBudgetAccess
import com.firkatdavletov.financeconsultante.ledger.application.BudgetRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

@Service
class OfflineSyncService(
    private val access: FamilyBudgetAccess,
    private val budgets: BudgetRepository,
    private val repository: OfflineSyncRepository,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun changes(
        telegramUserId: Long,
        cursor: String?,
        limit: Int,
        protocolVersion: Int = 1,
    ): SyncPage {
        if (limit !in 1..MAX_PAGE_SIZE) invalid("Invalid page size")
        val member = access.activeMember(telegramUserId) ?: throw MobileApiAccessDenied()
        val account = budgets.findOwnedAccount(member.householdId, member.id) ?: throw MobileApiAccessDenied()
        val scope = SyncScope(member.householdId, member.id, member.role.name, account.accountingStartedAt)
        val current = repository.revision(member.householdId)
        val position = decode(cursor, scope, protocolVersion)
        val through = position.second.takeUnless { it == 0L } ?: current
        if (position.first > through || through > current) invalid("Invalid sync position")
        val rows =
            if (protocolVersion == 2) {
                repository.extendedChanges(member.householdId, member.id, position.first, through, limit + 1)
            } else {
                repository.changes(member.householdId, member.id, position.first, through, limit + 1)
            }
        val page = rows.take(limit)
        val hasMore = rows.size > limit
        val after = if (hasMore) page.last().revision else through
        val prefix = if (protocolVersion == 2) "v2:" else ""
        val next = "$prefix${scope.householdId}:${scope.memberId}:$after:${if (hasMore) through else 0}"
        return SyncPage(
            scope,
            page,
            Base64.getUrlEncoder().withoutPadding().encodeToString(next.toByteArray(UTF_8)),
            hasMore,
            through,
        )
    }

    private fun decode(
        cursor: String?,
        scope: SyncScope,
        protocolVersion: Int,
    ): Pair<Long, Long> {
        if (cursor == null) return 0L to 0L
        val parts =
            runCatching {
                require(cursor.length <= MAX_CURSOR_LENGTH)
                val decoded = String(Base64.getUrlDecoder().decode(cursor), UTF_8)
                val value =
                    if (protocolVersion == 2) {
                        require(decoded.startsWith("v2:"))
                        decoded.removePrefix("v2:")
                    } else {
                        decoded
                    }
                value.split(':').also { require(it.size == 4) }
            }.getOrElse { invalid("Invalid sync cursor") }
        if (parts[0] != scope.householdId.toString() || parts[1] != scope.memberId.toString()) {
            throw OfflineSyncConflict("scope_changed")
        }
        val after = parts[2].toLongOrNull()?.takeIf { it >= 0 } ?: invalid("Invalid sync cursor")
        val through = parts[3].toLongOrNull()?.takeIf { it >= 0 } ?: invalid("Invalid sync cursor")
        return after to through
    }

    private fun invalid(message: String): Nothing = throw InvalidOfflineRequest(message)

    companion object {
        const val MAX_PAGE_SIZE = 200
        private const val MAX_CURSOR_LENGTH = 256
    }
}
