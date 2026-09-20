package com.firkatdavletov.financeconsultante.telegram.application

import java.util.UUID

data class EntryCorrectionDialog(
    val householdId: UUID,
    val memberId: UUID,
    val entryId: UUID,
    val version: Long,
) {
    init {
        require(version >= 0)
    }
}

interface EntryCorrectionDialogRepository {
    fun find(ownerId: Long): EntryCorrectionDialog?

    fun save(
        ownerId: Long,
        dialog: EntryCorrectionDialog,
    )

    fun clear(ownerId: Long)
}
