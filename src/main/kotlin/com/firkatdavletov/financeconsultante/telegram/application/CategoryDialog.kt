package com.firkatdavletov.financeconsultante.telegram.application

import java.util.UUID

enum class CategoryDialogStep {
    ADD_NAME,
    ADD_ICON,
    RENAME,
}

data class CategoryDialog(
    val step: CategoryDialogStep,
    val screenMessageId: Long,
    val categoryId: UUID? = null,
    val pendingName: String? = null,
) {
    init {
        require(screenMessageId > 0)
        require((step == CategoryDialogStep.RENAME) == (categoryId != null))
        require((step == CategoryDialogStep.ADD_ICON) == (pendingName != null))
    }
}

interface CategoryDialogRepository {
    fun find(ownerId: Long): CategoryDialog?

    fun save(
        ownerId: Long,
        dialog: CategoryDialog,
    )

    fun clear(ownerId: Long)
}
