package com.firkatdavletov.financeconsultante.telegram.application

import com.firkatdavletov.financeconsultante.access.domain.MemberRole
import com.firkatdavletov.financeconsultante.ledger.application.CategoryManagement
import com.firkatdavletov.financeconsultante.ledger.application.CorrectionResult
import com.firkatdavletov.financeconsultante.ledger.application.EntryCorrections
import com.firkatdavletov.financeconsultante.ledger.application.EntryTarget
import com.firkatdavletov.financeconsultante.ledger.application.LedgerOperations
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import com.firkatdavletov.financeconsultante.ledger.domain.EntryChange
import com.firkatdavletov.financeconsultante.ledger.domain.EntryStatus
import com.firkatdavletov.financeconsultante.ledger.domain.LedgerEntry
import com.firkatdavletov.financeconsultante.ledger.domain.MessageParseResult.Command
import com.firkatdavletov.financeconsultante.shared.domain.Money
import java.time.Clock
import java.time.DateTimeException
import java.time.LocalDate
import java.util.UUID

@Suppress(
    "ReturnCount",
    "MagicNumber",
    "TooManyFunctions",
) // Fixed command arities and delegated category/correction state machines share this router.
class TelegramCorrectionHandler(
    private val access: OwnerAccess,
    private val operations: LedgerOperations,
    private val corrections: EntryCorrections,
    private val categories: CategoryManagement,
    private val clock: Clock,
    private val categoryDialogs: CategoryDialogRepository? = null,
    private val entryDialogs: EntryCorrectionDialogRepository? = null,
) {
    private val categoryHandler = TelegramCategoryHandler(categories, categoryDialogs, clock)

    fun conversationInput(
        message: InboxMessage,
        botId: Long,
    ): TelegramReply? = amountInput(message, botId) ?: categoryHandler.input(message)

    fun clearConversations(ownerId: Long) {
        entryDialogs?.clear(ownerId)
        categoryDialogs?.clear(ownerId)
    }

    fun callback(
        message: InboxMessage,
        botId: Long,
    ): TelegramReply {
        require(access.canProcess(message.ownerId, message.memberId)) { "Budget access denied" }
        if (message.text.substringBefore(':') in TelegramCategoryHandler.CALLBACKS) {
            entryDialogs?.clear(message.ownerId)
            return categoryHandler.callback(message, botId)
        }
        val parts = message.text.split(':')
        if (parts.size != 3 || parts[0] !in CALLBACKS) return "Кнопка недоступна. /history".reply()
        val target = target(message.ownerId, parts.drop(1)) ?: return EntryCorrections.STALE.reply()
        val entry = corrections.selected(target) ?: return EntryCorrections.STALE.reply()
        val args = "${entry.id} ${entry.version}"
        entryDialogs?.clear(message.ownerId)
        return when (parts[0]) {
            "void" -> {
                result(corrections.change(target, EntryChange.Void, source(message, botId)), message.ownerId)
            }

            "entry" -> {
                entryReply(entry, message.ownerId)
            }

            "amount" -> {
                val store = entryDialogs
                if (store == null) {
                    "Новая сумма в ${entry.originalAmount.currency}:\n/amount $args <сумма>".reply()
                } else {
                    categoryDialogs?.clear(message.ownerId)
                    store.save(
                        message.ownerId,
                        EntryCorrectionDialog(
                            target.householdId,
                            target.memberId,
                            target.entryId,
                            target.version,
                        ),
                    )
                    amountPrompt(entry)
                }
            }

            "cat" -> {
                "Выберите ID категории из /categories:\n/category $args <id категории>".reply()
            }

            "date" -> {
                "Новая дата:\n/date $args <ГГГГ-ММ-ДД>".reply()
            }

            else -> {
                "Возврат в ${entry.originalAmount.currency}:\n/refund $args <сумма> [ГГГГ-ММ-ДД]".reply()
            }
        }
    }

    @Suppress("ReturnCount") // Only an active amount dialog consumes ordinary text.
    private fun amountInput(
        message: InboxMessage,
        botId: Long,
    ): TelegramReply? {
        val store = entryDialogs ?: return null
        val dialog = store.find(message.ownerId) ?: return null
        if (message.text.startsWith('/')) {
            store.clear(message.ownerId)
            return if (message.text.trim().equals("/cancel", ignoreCase = true)) {
                "Редактирование суммы отменено.".reply()
            } else {
                null
            }
        }
        val owner = operations.owned(message.ownerId)
        if (owner == null || owner.householdId != dialog.householdId || owner.memberId != dialog.memberId) {
            store.clear(message.ownerId)
            return EntryCorrections.STALE.reply()
        }
        val target =
            EntryTarget(
                dialog.householdId,
                dialog.memberId,
                dialog.entryId,
                dialog.version,
                owner.role,
            )
        val entry = corrections.selected(target)
        if (entry == null) {
            store.clear(message.ownerId)
            return EntryCorrections.STALE.reply()
        }
        val value = amount(message.text.trim(), entry) ?: return amountPrompt(entry, AMOUNT_HELP)
        val changed = corrections.change(target, EntryChange.Amount(value), source(message, botId))
        store.clear(message.ownerId)
        return result(changed, message.ownerId)
    }

    fun command(
        message: InboxMessage,
        command: Command,
        botId: Long,
    ): TelegramReply? {
        require(access.canProcess(message.ownerId, message.memberId)) { "Budget access denied" }
        if (command.name !in COMMANDS) return null
        if (operations.owned(message.ownerId) == null) return "Сначала завершите настройку: /start.".reply()
        val parts = command.arguments.split(' ').filter { it.isNotEmpty() }
        return if (command.name in CATEGORY_COMMANDS) {
            val owner = checkNotNull(operations.owned(message.ownerId))
            if (!owner.canManageHousehold) {
                "Категориями и правилами управляет только OWNER.".reply()
            } else {
                categoryHandler.handle(message.ownerId, command.name, parts)
            }
        } else {
            entryCommand(message, command.name, parts, botId)
        }
    }

    @Suppress("CyclomaticComplexMethod") // Dispatches the five explicit correction commands and their validation.
    private fun entryCommand(
        message: InboxMessage,
        name: String,
        parts: List<String>,
        botId: Long,
    ): TelegramReply {
        val target = target(message.ownerId, parts) ?: return "Выберите запись в /history и скопируйте команду.".reply()
        val entry = corrections.selected(target) ?: return EntryCorrections.STALE.reply()
        val source = source(message, botId)
        val change =
            when {
                name == "/void" && parts.size == 2 -> {
                    EntryChange.Void
                }

                name == "/amount" && parts.size >= 3 -> {
                    EntryChange.Amount(amount(parts.drop(2).joinToString(" "), entry) ?: return AMOUNT_HELP.reply())
                }

                name == "/date" && parts.size == 3 -> {
                    EntryChange.Date(date(parts[2]) ?: return DATE_HELP.reply())
                }

                name == "/category" && parts.size == 3 -> {
                    EntryChange.Category(uuid(parts[2]) ?: return "Нужен ID из /categories.".reply())
                }

                else -> {
                    null
                }
            }
        if (change != null) {
            val changed = corrections.change(target, change, source)
            val reply = result(changed, message.ownerId)
            return if (
                change is EntryChange.Category && changed is CorrectionResult.Updated && target.role == MemberRole.OWNER
            ) {
                reply.copy(
                    text =
                        reply.text +
                            "\nСохранить правило для слова:\n/rule ${entry.id} ${changed.entry.version} <слово>",
                )
            } else {
                reply
            }
        }
        return when {
            name == "/refund" && parts.size >= 3 -> {
                refund(message, parts, entry, target, source)
            }

            name == "/entry" && parts.size == 2 -> {
                entryReply(entry, message.ownerId)
            }

            else -> {
                "Проверьте формат команды из кнопки в /history.".reply()
            }
        }
    }

    private fun refund(
        message: InboxMessage,
        parts: List<String>,
        entry: LedgerEntry,
        target: EntryTarget,
        source: String,
    ): TelegramReply {
        val hasDate = parts.last().contains('-')
        val date =
            if (hasDate) {
                date(parts.last())
            } else {
                message.sentAt
                    ?.takeIf { it <= clock.instant() }
                    ?.atZone(LedgerOperations.ZONE)
                    ?.toLocalDate()
            }
        if (date == null) return DATE_HELP.reply()
        val amountParts = if (hasDate) parts.drop(2).dropLast(1) else parts.drop(2)
        val amount = amount(amountParts.joinToString(" "), entry) ?: return AMOUNT_HELP.reply()
        return result(corrections.refund(target, amount, date, source), message.ownerId)
    }

    private fun target(
        ownerId: Long,
        parts: List<String>,
    ): EntryTarget? {
        if (parts.size < 2) return null
        val id = uuid(parts[0]) ?: return null
        val version = parts[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val owner = operations.owned(ownerId) ?: return null
        return EntryTarget(owner.householdId, owner.memberId, id, version, owner.role)
    }

    private fun result(
        result: CorrectionResult,
        ownerId: Long,
    ): TelegramReply =
        when (result) {
            is CorrectionResult.Rejected -> result.message.reply()
            is CorrectionResult.Updated -> entryReply(result.entry, ownerId, "Сохранено")
        }

    private fun entryReply(
        entry: LedgerEntry,
        ownerId: Long,
        prefix: String = "Операция",
    ): TelegramReply =
        TelegramReply(
            "$prefix: ${LedgerMessages.line(entry, categories.name(ownerId, entry.categoryId))}\n" +
                "Операция ${entry.id}, версия ${entry.version}. " +
                (entry.refundOf?.let { "Возврат расхода $it. " } ?: "") +
                "Аудит сохранён. /balance; /history",
            EntryButtons.actions(entry),
        )

    private fun amountPrompt(
        entry: LedgerEntry,
        error: String? = null,
    ): TelegramReply =
        listOfNotNull(
            error,
            "Введите новую сумму в ${entry.originalAmount.currency}.",
            "Например: 1000 или 1000,50\nДля отмены: /cancel",
        ).joinToString("\n\n").reply()

    @Suppress("SwallowedException")
    private fun amount(
        text: String,
        entry: LedgerEntry,
    ): Money? =
        try {
            if (!AMOUNT.matches(text)) {
                null
            } else {
                Money
                    .of(
                        text.replace(" ", "").replace(',', '.'),
                        entry.originalAmount.currency,
                    ).requirePositive()
            }
        } catch (_: IllegalArgumentException) {
            null
        }

    @Suppress("SwallowedException")
    private fun date(text: String): LocalDate? =
        try {
            if (!ISO_DATE.matches(text)) null else LocalDate.parse(text).takeIf { it.year >= 1 }
        } catch (_: DateTimeException) {
            null
        }

    private fun uuid(text: String): UUID? = if (UUID_PATTERN.matches(text)) UUID.fromString(text) else null

    private fun source(
        message: InboxMessage,
        botId: Long,
    ) = "telegram:$botId:inbox:${message.id}"

    companion object {
        private const val AMOUNT_HELP = "Нужна положительная сумма до двух дробных знаков в валюте исходной записи."
        private const val DATE_HELP = "Укажите действительную дату ГГГГ-ММ-ДД не позже сегодня."
        private val AMOUNT = Regex("(?:[0-9]{1,3}(?: [0-9]{3})+|[0-9]+)(?:[.,][0-9]{1,2})?")
        private val ISO_DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
        private val UUID_PATTERN = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private val CALLBACKS = setOf("entry", "amount", "cat", "date", "void", "refund")
        private val CATEGORY_COMMANDS = TelegramCategoryHandler.COMMANDS
        private val COMMANDS = CATEGORY_COMMANDS + setOf("/entry", "/amount", "/category", "/date", "/void", "/refund")
    }
}
