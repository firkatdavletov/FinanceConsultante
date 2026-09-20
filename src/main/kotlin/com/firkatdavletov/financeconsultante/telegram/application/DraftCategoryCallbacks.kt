package com.firkatdavletov.financeconsultante.telegram.application

import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID

internal data class DraftCategoryPage(
    val draftId: UUID,
    val version: Long,
    val page: Int,
)

internal data class DraftCategorySelection(
    val draftId: UUID,
    val version: Long,
    val categoryId: UUID,
)

internal data class DraftNewCategory(
    val draftId: UUID,
    val version: Long,
)

internal object DraftCategoryCallbacks {
    fun page(
        draftId: UUID,
        version: Long,
        page: Int,
    ): String = "dcp:${encode(draftId)}:$version:$page"

    fun selection(
        draftId: UUID,
        version: Long,
        categoryId: UUID,
    ): String = "dcs:${encode(draftId)}:$version:${encode(categoryId)}"

    fun newCategory(
        draftId: UUID,
        version: Long,
    ): String = "dcn:${encode(draftId)}:$version"

    @Suppress("ReturnCount") // Every compact callback field is independently untrusted.
    fun parsePage(value: String): DraftCategoryPage? {
        val match = PAGE.matchEntire(value) ?: return null
        return DraftCategoryPage(
            decode(match.groupValues[1]) ?: return null,
            match.groupValues[2].toLongOrNull() ?: return null,
            match.groupValues[LAST_VALUE_GROUP].toIntOrNull() ?: return null,
        )
    }

    @Suppress("ReturnCount") // Every compact callback field is independently untrusted.
    fun parseSelection(value: String): DraftCategorySelection? {
        val match = SELECTION.matchEntire(value) ?: return null
        return DraftCategorySelection(
            decode(match.groupValues[1]) ?: return null,
            match.groupValues[2].toLongOrNull() ?: return null,
            decode(match.groupValues[LAST_VALUE_GROUP]) ?: return null,
        )
    }

    @Suppress("ReturnCount") // Both compact callback fields are independently untrusted.
    fun parseNewCategory(value: String): DraftNewCategory? {
        val match = NEW_CATEGORY.matchEntire(value) ?: return null
        return DraftNewCategory(
            decode(match.groupValues[1]) ?: return null,
            match.groupValues[2].toLongOrNull() ?: return null,
        )
    }

    private fun encode(value: UUID): String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                ByteBuffer
                    .allocate(
                        UUID_BYTES,
                    ).putLong(value.mostSignificantBits)
                    .putLong(value.leastSignificantBits)
                    .array(),
            )

    private fun decode(value: String): UUID? =
        runCatching {
            val bytes = Base64.getUrlDecoder().decode(value)
            if (bytes.size != UUID_BYTES) return null
            val buffer = ByteBuffer.wrap(bytes)
            UUID(buffer.long, buffer.long)
        }.getOrNull()

    private const val UUID_BYTES = 16
    private const val LAST_VALUE_GROUP = 3
    private const val COMPACT_UUID = "[A-Za-z0-9_-]{22}"
    private val PAGE = Regex("dcp:($COMPACT_UUID):([0-9]+):([0-9]+)")
    private val SELECTION = Regex("dcs:($COMPACT_UUID):([0-9]+):($COMPACT_UUID)")
    private val NEW_CATEGORY = Regex("dcn:($COMPACT_UUID):([0-9]+)")
}
