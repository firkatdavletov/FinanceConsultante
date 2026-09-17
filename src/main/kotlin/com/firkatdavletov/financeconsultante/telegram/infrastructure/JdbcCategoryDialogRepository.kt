package com.firkatdavletov.financeconsultante.telegram.infrastructure

import com.firkatdavletov.financeconsultante.telegram.application.CategoryDialog
import com.firkatdavletov.financeconsultante.telegram.application.CategoryDialogRepository
import com.firkatdavletov.financeconsultante.telegram.application.CategoryDialogStep
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Repository
@Transactional(propagation = Propagation.MANDATORY)
class JdbcCategoryDialogRepository(
    private val jdbc: JdbcClient,
) : CategoryDialogRepository {
    override fun find(ownerId: Long): CategoryDialog? =
        jdbc
            .sql("SELECT * FROM telegram_category_dialog WHERE owner_id = ? FOR UPDATE")
            .param(ownerId)
            .query { row, _ ->
                CategoryDialog(
                    CategoryDialogStep.valueOf(row.getString("step")),
                    row.getLong("screen_message_id"),
                    row.getObject("category_id", UUID::class.java),
                    row.getString("pending_name"),
                )
            }.optional()
            .orElse(null)

    override fun save(
        ownerId: Long,
        dialog: CategoryDialog,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO telegram_category_dialog(owner_id, step, category_id, pending_name, screen_message_id)
                VALUES (:owner, :step, :category, :name, :message)
                ON CONFLICT (owner_id) DO UPDATE SET step = EXCLUDED.step, category_id = EXCLUDED.category_id,
                    pending_name = EXCLUDED.pending_name, screen_message_id = EXCLUDED.screen_message_id
                """.trimIndent(),
            ).param("owner", ownerId)
            .param("step", dialog.step.name)
            .param("category", dialog.categoryId)
            .param("name", dialog.pendingName)
            .param("message", dialog.screenMessageId)
            .update()
    }

    override fun clear(ownerId: Long) {
        jdbc.sql("DELETE FROM telegram_category_dialog WHERE owner_id = ?").param(ownerId).update()
    }
}
