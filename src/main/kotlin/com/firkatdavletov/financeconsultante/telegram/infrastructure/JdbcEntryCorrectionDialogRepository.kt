package com.firkatdavletov.financeconsultante.telegram.infrastructure

import com.firkatdavletov.financeconsultante.telegram.application.EntryCorrectionDialog
import com.firkatdavletov.financeconsultante.telegram.application.EntryCorrectionDialogRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Repository
@Transactional(propagation = Propagation.MANDATORY)
class JdbcEntryCorrectionDialogRepository(
    private val jdbc: JdbcClient,
) : EntryCorrectionDialogRepository {
    override fun find(ownerId: Long): EntryCorrectionDialog? =
        jdbc
            .sql("SELECT * FROM telegram_entry_correction_dialog WHERE owner_id = ? FOR UPDATE")
            .param(ownerId)
            .query { row, _ ->
                EntryCorrectionDialog(
                    row.getObject("household_id", UUID::class.java),
                    row.getObject("member_id", UUID::class.java),
                    row.getObject("entry_id", UUID::class.java),
                    row.getLong("entry_version"),
                )
            }.optional()
            .orElse(null)

    override fun save(
        ownerId: Long,
        dialog: EntryCorrectionDialog,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO telegram_entry_correction_dialog(
                    owner_id, household_id, member_id, entry_id, entry_version
                ) VALUES (:owner, :household, :member, :entry, :version)
                ON CONFLICT (owner_id) DO UPDATE SET household_id = EXCLUDED.household_id,
                    member_id = EXCLUDED.member_id, entry_id = EXCLUDED.entry_id,
                    entry_version = EXCLUDED.entry_version
                """.trimIndent(),
            ).param("owner", ownerId)
            .param("household", dialog.householdId)
            .param("member", dialog.memberId)
            .param("entry", dialog.entryId)
            .param("version", dialog.version)
            .update()
    }

    override fun clear(ownerId: Long) {
        jdbc.sql("DELETE FROM telegram_entry_correction_dialog WHERE owner_id = ?").param(ownerId).update()
    }
}
