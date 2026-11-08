package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import com.firkatdavletov.financeconsultante.mobileapi.application.CategoryUsageEvent
import com.firkatdavletov.financeconsultante.mobileapi.application.CategoryUsageStore
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetActor
import com.firkatdavletov.financeconsultante.mobileapi.application.PositionedCategoryUsageEvent
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class JdbcCategoryUsageStore(
    private val jdbc: JdbcClient,
) : CategoryUsageStore {
    override fun lockFeed(actor: CloudBudgetActor) {
        jdbc
            .sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
            .param("category-usage:${actor.householdId}:${actor.memberId}")
            .query { _, _ -> Unit }
            .single()
    }

    override fun accept(
        actor: CloudBudgetActor,
        event: CategoryUsageEvent,
    ): Boolean {
        val fields =
            listOf(actor.householdId, actor.memberId, event.id, event.operationId, event.categoryId, event.usedAtMillis)
        val inserted =
            jdbc
                .sql(
                    """
                    INSERT INTO category_usage_event(household_id, member_id, id, operation_id, category_id, used_at_millis)
                    VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                    """.trimIndent(),
                ).params(fields)
                .update()
        return inserted == 1 ||
            jdbc
                .sql(
                    """
                    SELECT EXISTS (
                        SELECT 1 FROM category_usage_event WHERE household_id = ? AND member_id = ?
                            AND id = ? AND operation_id = ? AND category_id = ? AND used_at_millis = ?
                    )
                    """.trimIndent(),
                ).params(fields)
                .query(Boolean::class.java)
                .single()
    }

    override fun after(
        actor: CloudBudgetActor,
        position: Long,
        limit: Int,
    ): List<PositionedCategoryUsageEvent> =
        jdbc
            .sql(
                """
                SELECT position, id, operation_id, category_id, used_at_millis
                FROM category_usage_event WHERE household_id = ? AND member_id = ? AND position > ?
                ORDER BY position LIMIT ?
                """.trimIndent(),
            ).params(actor.householdId, actor.memberId, position, limit)
            .query { row, _ ->
                PositionedCategoryUsageEvent(
                    row.getLong("position"),
                    CategoryUsageEvent(
                        row.getObject("id", UUID::class.java),
                        row.getObject("operation_id", UUID::class.java),
                        row.getObject("category_id", UUID::class.java),
                        row.getLong("used_at_millis"),
                    ),
                )
            }.list()
}
