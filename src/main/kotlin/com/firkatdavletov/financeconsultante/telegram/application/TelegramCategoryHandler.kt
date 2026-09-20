package com.firkatdavletov.financeconsultante.telegram.application

import com.firkatdavletov.financeconsultante.ledger.application.CategoryDetails
import com.firkatdavletov.financeconsultante.ledger.application.CategoryManagement
import com.firkatdavletov.financeconsultante.ledger.application.LedgerOperations
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryIcon
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import com.firkatdavletov.financeconsultante.ledger.domain.TransactionCategory
import com.firkatdavletov.financeconsultante.shared.domain.Money
import java.time.Clock
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.util.Locale
import java.util.UUID

@Suppress(
    "MagicNumber",
    "ReturnCount",
    "TooManyFunctions",
) // A compact state-machine handler owns the bounded category screens and their renderers.
class TelegramCategoryHandler(
    private val categories: CategoryManagement,
    private val dialogs: CategoryDialogRepository? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Suppress("CyclomaticComplexMethod", "ReturnCount") // Reject invalid legacy commands before writing.
    fun handle(
        ownerId: Long,
        name: String,
        parts: List<String>,
    ): TelegramReply {
        val id = parts.firstOrNull()?.let(::uuid)
        val categoryKind = parts.firstOrNull()?.lowercase(Locale.ROOT)
        return when {
            name == "/categories" -> {
                val page = page(parts) ?: return "Формат: /categories [страница].".reply()
                dialogs?.clear(ownerId)
                list(ownerId, page)
            }

            name == "/category_add" && parts.size >= 2 && categoryKind in setOf("expense", "income") -> {
                categories
                    .add(
                        ownerId,
                        parts.drop(1).joinToString(" "),
                        if (categoryKind == "income") CategoryKind.INCOME else CategoryKind.EXPENSE,
                    ).reply()
            }

            name in setOf("/category_archive", "/category_restore") && parts.size == 1 && id != null -> {
                categories.archive(ownerId, id, name == "/category_archive").reply()
            }

            name == "/category_rename" && parts.size >= 2 && id != null -> {
                categories.rename(ownerId, id, parts.drop(1).joinToString(" ")).reply()
            }

            name == "/rule" && parts.size >= 3 && id != null -> {
                categories
                    .saveRule(
                        ownerId,
                        id,
                        parts[1].toLongOrNull() ?: return "Некорректная версия.".reply(),
                        parts.drop(2).joinToString(" "),
                    ).reply()
            }

            name == "/rules" -> {
                val page = page(parts) ?: return "Формат: /rules [страница].".reply()
                val rows = categories.rules(ownerId)
                (
                    "Правила, страница $page:\n" +
                        rows.drop(page * PAGE_SIZE).take(PAGE_SIZE).joinToString("\n") {
                            "${it.keyword} → ${it.categoryId}"
                        } + "\n/rule_delete <слово или фраза>" +
                        if (rows.size > (page + 1) * PAGE_SIZE) "\n/rules ${page + 1}" else ""
                ).reply()
            }

            name == "/rule_delete" && parts.isNotEmpty() -> {
                categories.deleteRule(ownerId, parts.joinToString(" ")).reply()
            }

            else -> {
                "Проверьте формат. /categories — управление категориями; /rules — правила.".reply()
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount") // Callback names form the category screen state machine.
    fun callback(
        message: InboxMessage,
        botId: Long,
    ): TelegramReply {
        val messageId = message.messageId ?: return "Экран недоступен. Откройте /categories заново.".reply()
        val parts = message.text.split(':')
        if (parts.firstOrNull() != CALLBACK_PREFIX) return stale(messageId)
        return when (parts.getOrNull(1)) {
            "list" -> {
                dialogs?.clear(message.ownerId)
                list(message.ownerId, parts.getOrNull(2)?.toIntOrNull() ?: 0).edit(messageId)
            }

            "close" -> {
                dialogs?.clear(message.ownerId)
                "Раздел категорий закрыт.\nОткрыть снова: /categories".reply().edit(messageId)
            }

            "add" -> {
                val store = dialogs ?: return unavailable(messageId)
                store.save(message.ownerId, CategoryDialog(CategoryDialogStep.ADD_NAME, messageId))
                addPrompt().edit(messageId)
            }

            "view" -> {
                card(message.ownerId, categoryId(parts) ?: return stale(messageId)).edit(messageId)
            }

            "rename" -> {
                beginRename(message.ownerId, categoryId(parts), messageId)
            }

            "icons" -> {
                existingIconPicker(message.ownerId, categoryId(parts), messageId)
            }

            "newicon" -> {
                createWithIcon(message.ownerId, parts.getOrNull(2), messageId)
            }

            "seticon" -> {
                setIcon(message.ownerId, categoryId(parts), parts.getOrNull(3), messageId)
            }

            "delete" -> {
                deleteConfirmation(message.ownerId, categoryId(parts), messageId)
            }

            "delete_yes" -> {
                delete(message.ownerId, categoryId(parts), botId, message.id, messageId)
            }

            else -> {
                stale(messageId)
            }
        }
    }

    @Suppress("ReturnCount") // Only an active category dialog consumes ordinary text.
    fun input(message: InboxMessage): TelegramReply? {
        val store = dialogs ?: return null
        val dialog = store.find(message.ownerId) ?: return null
        if (message.text.startsWith('/')) {
            store.clear(message.ownerId)
            return if (message.text.trim().equals("/cancel", ignoreCase = true)) {
                list(message.ownerId, 0).edit(dialog.screenMessageId)
            } else {
                null
            }
        }
        val text = message.text.trim()
        return when (dialog.step) {
            CategoryDialogStep.ADD_NAME -> {
                categories.nameError(message.ownerId, text)?.let { error ->
                    return addPrompt(error).edit(dialog.screenMessageId)
                }
                store.save(
                    message.ownerId,
                    dialog.copy(step = CategoryDialogStep.ADD_ICON, pendingName = text),
                )
                iconPicker(text, creation = true).edit(dialog.screenMessageId)
            }

            CategoryDialogStep.ADD_ICON -> {
                iconPicker(checkNotNull(dialog.pendingName), creation = true, error = "Выберите иконку кнопкой.")
                    .edit(dialog.screenMessageId)
            }

            CategoryDialogStep.RENAME -> {
                val id = checkNotNull(dialog.categoryId)
                categories.nameError(message.ownerId, text, id)?.let { error ->
                    return renamePrompt(message.ownerId, id, error).edit(dialog.screenMessageId)
                }
                val renamed =
                    categories.renameCategory(message.ownerId, id, text) ?: return stale(dialog.screenMessageId)
                store.clear(message.ownerId)
                card(message.ownerId, renamed.id).edit(dialog.screenMessageId)
            }
        }
    }

    private fun list(
        ownerId: Long,
        requestedPage: Int,
    ): TelegramReply {
        val all = categories.activeCategories(ownerId)
        val lastPage = ((all.size - 1).coerceAtLeast(0)) / PAGE_SIZE
        val page = requestedPage.coerceIn(0, lastPage)
        val visible = all.drop(page * PAGE_SIZE).take(PAGE_SIZE)
        var row = 0
        val buttons =
            buildList {
                visible.forEach { category ->
                    add(ReplyButton("${category.icon.emoji} ${category.name}", "cats:view:${category.id}", row++))
                }
                if (lastPage > 0) {
                    val navigationRow = row++
                    if (page > 0) add(ReplyButton("◀️", "cats:list:${page - 1}", navigationRow))
                    if (page < lastPage) add(ReplyButton("▶️", "cats:list:${page + 1}", navigationRow))
                }
                add(ReplyButton("➕ Добавить категорию", "cats:add", row++))
                add(ReplyButton("◀️ Назад", "cats:close", row))
            }
        val names = visible.joinToString("\n") { "${it.icon.emoji} ${it.name}" }
        val pageLabel = if (lastPage > 0) "\n\nСтраница ${page + 1} из ${lastPage + 1}." else ""
        return TelegramReply(
            "Категории\n\n" +
                if (names.isEmpty()) {
                    "Пока нет категорий."
                } else {
                    "$names$pageLabel\n\nВыберите категорию:"
                },
            buttons,
        )
    }

    private fun card(
        ownerId: Long,
        categoryId: UUID,
    ): TelegramReply {
        val details = categories.details(ownerId, categoryId, currentMonth()) ?: return staleReply()
        val category = details.category
        var row = 0
        val system = category.dictionaryKey == TransactionCategory.OTHER.name
        val buttons =
            buildList {
                if (!system) {
                    add(ReplyButton("✏️ Изменить название", "cats:rename:${category.id}", row++))
                }
                add(ReplyButton("😀 Изменить иконку", "cats:icons:${category.id}", row++))
                if (!system && category.kind == CategoryKind.EXPENSE) {
                    add(ReplyButton("🗑 Удалить", "cats:delete:${category.id}", row++))
                }
                add(ReplyButton("◀️ К категориям", "cats:list", row))
            }
        return TelegramReply(cardText(details, system), buttons)
    }

    private fun cardText(
        details: CategoryDetails,
        system: Boolean,
    ): String =
        buildString {
            append(details.category.icon.emoji).append(' ').append(details.category.name).append("\n\n")
            val amountLabel = if (details.category.kind == CategoryKind.INCOME) "Доходов" else "Расходов"
            append(amountLabel).append(" за ").append(monthName(currentMonth().month)).append(": ")
            append(money(details.usage.monthAmount)).append('\n')
            append("Операций: ").append(details.usage.monthOperations).append("\n\n")
            if (system) append("Сюда переносятся расходы из удалённых категорий.\n\n")
            append("ID категории: ").append(details.category.id)
        }

    private fun beginRename(
        ownerId: Long,
        categoryId: UUID?,
        messageId: Long,
    ): TelegramReply {
        val id = categoryId ?: return stale(messageId)
        val category = categories.category(ownerId, id) ?: return stale(messageId)
        if (category.dictionaryKey == TransactionCategory.OTHER.name) return card(ownerId, id).edit(messageId)
        val store = dialogs ?: return unavailable(messageId)
        store.save(ownerId, CategoryDialog(CategoryDialogStep.RENAME, messageId, categoryId = id))
        return renamePrompt(ownerId, id).edit(messageId)
    }

    private fun renamePrompt(
        ownerId: Long,
        categoryId: UUID,
        error: String? = null,
    ): TelegramReply {
        val category = categories.category(ownerId, categoryId) ?: return staleReply()
        return TelegramReply(
            listOfNotNull(
                error,
                "Текущее название: ${category.name}\n\nВведите новое название:",
            ).joinToString("\n\n"),
            listOf(ReplyButton("❌ Отмена", "cats:view:$categoryId", 0)),
        )
    }

    private fun existingIconPicker(
        ownerId: Long,
        categoryId: UUID?,
        messageId: Long,
    ): TelegramReply {
        val category = categoryId?.let { categories.category(ownerId, it) } ?: return stale(messageId)
        return iconPicker(category.name, creation = false, category.id).edit(messageId)
    }

    private fun createWithIcon(
        ownerId: Long,
        code: String?,
        messageId: Long,
    ): TelegramReply {
        val store = dialogs ?: return unavailable(messageId)
        val dialog = store.find(ownerId)
        if (dialog?.step != CategoryDialogStep.ADD_ICON || dialog.screenMessageId != messageId) {
            return stale(messageId)
        }
        val icon = icon(code) ?: return iconPicker(checkNotNull(dialog.pendingName), creation = true).edit(messageId)
        val category = categories.createExpense(ownerId, checkNotNull(dialog.pendingName), icon)
        if (category == null) {
            store.clear(ownerId)
            val list = list(ownerId, 0)
            return list.copy(text = "Не удалось создать категорию.\n\n${list.text}").edit(messageId)
        }
        store.clear(ownerId)
        return list(ownerId, pageOf(ownerId, category.id)).edit(messageId)
    }

    private fun setIcon(
        ownerId: Long,
        categoryId: UUID?,
        code: String?,
        messageId: Long,
    ): TelegramReply {
        val id = categoryId ?: return stale(messageId)
        val selected = icon(code) ?: return existingIconPicker(ownerId, id, messageId)
        categories.changeIcon(ownerId, id, selected) ?: return stale(messageId)
        return card(ownerId, id).edit(messageId)
    }

    private fun deleteConfirmation(
        ownerId: Long,
        categoryId: UUID?,
        messageId: Long,
    ): TelegramReply {
        val details = categoryId?.let { categories.details(ownerId, it, currentMonth()) } ?: return stale(messageId)
        if (
            details.category.dictionaryKey == TransactionCategory.OTHER.name ||
            details.category.kind != CategoryKind.EXPENSE
        ) {
            return card(ownerId, details.category.id).edit(messageId)
        }
        val category = details.category
        return TelegramReply(
            "Удалить категорию «${category.icon.emoji} ${category.name}»?\n\n" +
                "В ней находится ${details.usage.totalOperations} ${operationsWord(
                    details.usage.totalOperations,
                )}.\n\n" +
                "Сами расходы удалены не будут.\n" +
                "Они будут перенесены в категорию «Другое».",
            listOf(
                ReplyButton("🗑 Да, удалить", "cats:delete_yes:${category.id}", 0),
                ReplyButton("Отмена", "cats:view:${category.id}", 1),
            ),
        ).edit(messageId)
    }

    private fun delete(
        ownerId: Long,
        categoryId: UUID?,
        botId: Long,
        inboxId: Long,
        messageId: Long,
    ): TelegramReply {
        val id = categoryId ?: return stale(messageId)
        categories.delete(ownerId, id, "telegram:$botId:inbox:$inboxId:category-delete") ?: return stale(messageId)
        dialogs?.clear(ownerId)
        return list(ownerId, 0).edit(messageId)
    }

    private fun addPrompt(error: String? = null): TelegramReply =
        TelegramReply(
            listOfNotNull(
                error,
                "Введите название новой категории.\n\n" +
                    "Например:\nПродукты\nКафе\nМашина\nШкола",
            ).joinToString("\n\n"),
            listOf(ReplyButton("❌ Отмена", "cats:list", 0)),
        )

    private fun iconPicker(
        name: String,
        creation: Boolean,
        categoryId: UUID? = null,
        error: String? = null,
    ): TelegramReply {
        val prefix = if (creation) "cats:newicon:" else "cats:seticon:$categoryId:"
        val buttons =
            PICKER_ICONS
                .mapIndexed { index, icon ->
                    ReplyButton(icon.emoji, prefix + icon.name, index / ICONS_PER_ROW)
                }.toMutableList()
        val cancelRow = (PICKER_ICONS.size + ICONS_PER_ROW - 1) / ICONS_PER_ROW
        buttons +=
            ReplyButton(
                "❌ Отмена",
                if (creation) "cats:list" else "cats:view:$categoryId",
                cancelRow,
            )
        return TelegramReply(
            listOfNotNull(error, "Выберите иконку для категории «$name»:").joinToString("\n\n"),
            buttons,
        )
    }

    private fun pageOf(
        ownerId: Long,
        id: UUID,
    ): Int = categories.activeCategories(ownerId).indexOfFirst { it.id == id }.coerceAtLeast(0) / PAGE_SIZE

    private fun currentMonth(): YearMonth = YearMonth.from(LocalDate.now(clock.withZone(LedgerOperations.ZONE)))

    private fun money(value: Money): String {
        val plain = value.amount.stripTrailingZeros().toPlainString()
        val whole = plain.substringBefore('.')
        val fraction = plain.substringAfter('.', "")
        val sign = if (whole.startsWith('-')) "−" else ""
        val grouped =
            whole
                .removePrefix("-")
                .reversed()
                .chunked(3)
                .joinToString(" ")
                .reversed()
        return sign + grouped + if (fraction.isEmpty()) " ₸" else ",${fraction.padEnd(2, '0')} ₸"
    }

    private fun monthName(month: Month): String =
        listOf(
            "январь",
            "февраль",
            "март",
            "апрель",
            "май",
            "июнь",
            "июль",
            "август",
            "сентябрь",
            "октябрь",
            "ноябрь",
            "декабрь",
        )[month.value - 1]

    private fun operationsWord(count: Long): String {
        val lastTwo = count % 100
        val last = count % 10
        return when {
            lastTwo in 11L..14L -> "операций"
            last == 1L -> "операция"
            last in 2L..4L -> "операции"
            else -> "операций"
        }
    }

    private fun categoryId(parts: List<String>): UUID? = parts.getOrNull(2)?.let(::uuid)

    private fun icon(code: String?): CategoryIcon? =
        code?.let { value -> PICKER_ICONS.firstOrNull { it.name == value } }

    private fun page(parts: List<String>): Int? =
        if (parts.isEmpty()) 0 else parts.singleOrNull()?.toIntOrNull()?.takeIf { it in 0..MAX_PAGE }

    private fun uuid(text: String): UUID? = if (UUID_PATTERN.matches(text)) UUID.fromString(text) else null

    private fun stale(messageId: Long): TelegramReply = staleReply().edit(messageId)

    private fun staleReply(): TelegramReply =
        TelegramReply(
            "Экран устарел. Откройте список категорий.",
            listOf(ReplyButton("◀️ К категориям", "cats:list", 0)),
        )

    private fun unavailable(messageId: Long): TelegramReply =
        "Интерфейс категорий временно недоступен. Повторите /categories.".reply().edit(messageId)

    companion object {
        private const val CALLBACK_PREFIX = "cats"
        private const val PAGE_SIZE = 8
        private const val MAX_PAGE = 100
        private const val ICONS_PER_ROW = 4
        private val UUID_PATTERN = Regex("[A-Fa-f0-9]{8}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{12}")
        private val PICKER_ICONS = CategoryIcon.entries.filter { it != CategoryIcon.INCOME }
        val CALLBACKS = setOf(CALLBACK_PREFIX)
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
