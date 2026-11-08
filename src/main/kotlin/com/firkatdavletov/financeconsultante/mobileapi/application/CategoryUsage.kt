package com.firkatdavletov.financeconsultante.mobileapi.application

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.UUID

data class CategoryUsageEvent(
    val id: UUID,
    val operationId: UUID,
    val categoryId: UUID,
    val usedAtMillis: Long,
)

data class CategoryUsageReceipt(
    val scopeKey: String,
    val acceptedIds: List<UUID>,
)

data class CategoryUsagePage(
    val scopeKey: String,
    val events: List<CategoryUsageEvent>,
    val nextCursor: String,
    val hasMore: Boolean,
)

data class PositionedCategoryUsageEvent(
    val position: Long,
    val event: CategoryUsageEvent,
)

class CategoryUsageConflict : RuntimeException()

class CategoryUsageCursorExpired : RuntimeException()

interface CategoryUsageStore {
    fun lockFeed(actor: CloudBudgetActor)

    fun accept(
        actor: CloudBudgetActor,
        event: CategoryUsageEvent,
    ): Boolean

    fun after(
        actor: CloudBudgetActor,
        position: Long,
        limit: Int,
    ): List<PositionedCategoryUsageEvent>
}

@Service
class CategoryUsage(
    private val budgets: FirebaseBudgets,
    private val store: CategoryUsageStore,
    private val clock: Clock,
) {
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun push(
        uid: String,
        budgetId: UUID,
        events: List<CategoryUsageEvent>,
    ): CategoryUsageReceipt {
        val actor = budgets.actor(uid, budgetId)
        require(events.size in 1..CATEGORY_USAGE_PAGE_SIZE)
        // Positions are allocated only after acquiring the transaction lock for this feed.
        store.lockFeed(actor)
        val accepted =
            events.distinct().map { event ->
                if (!store.accept(actor, event)) throw CategoryUsageConflict()
                event.id
            }
        return CategoryUsageReceipt(actor.scopeKey(), accepted)
    }

    @Transactional
    fun pull(
        uid: String,
        budgetId: UUID,
        cursor: String?,
    ): CategoryUsagePage {
        val actor = budgets.actor(uid, budgetId)
        val scope = actor.scopeKey()
        val now = clock.instant()
        val after = cursor?.let { position(it, scope, now.epochSecond) } ?: 0
        val rows = store.after(actor, after, CATEGORY_USAGE_PAGE_SIZE + 1)
        val page = rows.take(CATEGORY_USAGE_PAGE_SIZE)
        val next = page.lastOrNull()?.position ?: after
        val expires = now.plus(CURSOR_LIFETIME).epochSecond
        val encoded =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString("$CURSOR_PREFIX$scope|$next|$expires".toByteArray(Charsets.UTF_8))
        return CategoryUsagePage(scope, page.map { it.event }, encoded, rows.size > CATEGORY_USAGE_PAGE_SIZE)
    }

    private fun position(
        cursor: String,
        scope: String,
        now: Long,
    ): Long {
        require(cursor.length <= MAX_CURSOR_LENGTH)
        val decoded = String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8)
        require(decoded.startsWith(CURSOR_PREFIX))
        val parts = decoded.removePrefix(CURSOR_PREFIX).split('|')
        require(parts.size == CURSOR_PARTS)
        val (cursorScope, position, expires) = parts
        if (cursorScope != scope) throw MobileApiAccessDenied()
        val after = position.toLong().also { require(it >= 0) }
        if (expires.toLong() <= now) throw CategoryUsageCursorExpired()
        return after
    }

    private fun CloudBudgetActor.scopeKey() = "$householdId:$memberId"

    companion object {
        private const val CURSOR_PREFIX = "category-usage-v1:"
        private const val CURSOR_PARTS = 3
        private const val MAX_CURSOR_LENGTH = 200
        private val CURSOR_LIFETIME = Duration.ofDays(30)
    }
}

internal const val CATEGORY_USAGE_PAGE_SIZE = 100
