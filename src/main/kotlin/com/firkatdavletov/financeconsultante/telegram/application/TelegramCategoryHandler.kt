package com.firkatdavletov.financeconsultante.telegram.application

import com.firkatdavletov.financeconsultante.ledger.application.CategoryManagement
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import java.util.Locale
import java.util.UUID

@Suppress("MagicNumber") // Fixed command arities and a bounded page size.
class TelegramCategoryHandler(
    private val categories: CategoryManagement,
) {
    @Suppress("CyclomaticComplexMethod", "ReturnCount") // Reject invalid commands before writing.
    fun handle(
        ownerId: Long,
        name: String,
        parts: List<String>,
    ): String {
        val id = parts.firstOrNull()?.let(::uuid)
        val categoryKind = parts.firstOrNull()?.lowercase(Locale.ROOT)
        return when {
            name == "/categories" -> {
                val page = page(parts) ?: return "Формат: /categories [страница]."
                val rows = categories.categories(ownerId)
                val body =
                    rows.drop(page * PAGE_SIZE).take(PAGE_SIZE).joinToString("\n") {
                        "${it.name} (${if (it.kind == CategoryKind.INCOME) "доход" else "расход"})" +
                            (if (it.archived) " [архив]" else "") + "\n${it.id}"
                    }
                "Категории, страница $page:\n$body\n/category_add expense|income <название>\n" +
                    "/category_archive <id>; /category_restore <id>; /category_rename <id> <название>" +
                    if (rows.size > (page + 1) * PAGE_SIZE) "\n/categories ${page + 1}" else ""
            }

            name == "/category_add" && parts.size >= 2 && categoryKind in setOf("expense", "income") -> {
                categories.add(
                    ownerId,
                    parts.drop(1).joinToString(" "),
                    if (categoryKind ==
                        "income"
                    ) {
                        CategoryKind.INCOME
                    } else {
                        CategoryKind.EXPENSE
                    },
                )
            }

            name in setOf("/category_archive", "/category_restore") && parts.size == 1 && id != null -> {
                categories.archive(ownerId, id, name == "/category_archive")
            }

            name == "/category_rename" && parts.size >= 2 && id != null -> {
                categories.rename(ownerId, id, parts.drop(1).joinToString(" "))
            }

            name == "/rule" && parts.size == 3 && id != null -> {
                categories.saveRule(ownerId, id, parts[1].toLongOrNull() ?: return "Некорректная версия.", parts[2])
            }

            name == "/rules" -> {
                val page = page(parts) ?: return "Формат: /rules [страница]."
                val rows = categories.rules(ownerId)
                "Правила, страница $page:\n" +
                    rows.drop(page * PAGE_SIZE).take(PAGE_SIZE).joinToString("\n") {
                        "${it.keyword} → ${it.categoryId}"
                    } + "\n/rule_delete <слово>" +
                    if (rows.size > (page + 1) * PAGE_SIZE) "\n/rules ${page + 1}" else ""
            }

            name == "/rule_delete" && parts.size == 1 -> {
                categories.deleteRule(ownerId, parts[0])
            }

            else -> {
                "Проверьте формат. /categories — управление категориями; /rules — правила."
            }
        }
    }

    private fun page(parts: List<String>): Int? =
        if (parts.isEmpty()) {
            0
        } else {
            parts.singleOrNull()?.toIntOrNull()?.takeIf { it in 0..MAX_PAGE }
        }

    private fun uuid(text: String): UUID? = if (UUID_PATTERN.matches(text)) UUID.fromString(text) else null

    companion object {
        private const val PAGE_SIZE = 10
        private const val MAX_PAGE = 100
        private val UUID_PATTERN = Regex("[A-Fa-f0-9]{8}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{12}")
        val COMMANDS =
            setOf(
                "/categories",
                "/category_add",
                "/category_archive",
                "/category_restore",
                "/category_rename",
                "/rule",
                "/rules",
                "/rule_delete",
            )
    }
}
