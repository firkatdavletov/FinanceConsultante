package com.firkatdavletov.financeconsultante.telegram.application

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

@Suppress("ReturnCount", "MagicNumber") // Fixed command arities; invalid input exits without changing the ledger.
class TelegramCorrectionHandler(
    private val access: OwnerAccess,
    private val operations: LedgerOperations,
    private val corrections: EntryCorrections,
    private val categories: CategoryManagement,
    private val clock: Clock,
) {
    private val categoryHandler = TelegramCategoryHandler(categories)

    fun callback(
        message: InboxMessage,
        botId: Long,
    ): TelegramReply {
        require(access.owns(message.ownerId)) { "Budget access denied" }
        val parts = message.text.split(':')
        if (parts.size != 3 || parts[0] !in CALLBACKS) return "Кнопка недоступна. /history".reply()
        val target = target(message.ownerId, parts.drop(1)) ?: return EntryCorrections.STALE.reply()
        val entry = corrections.selected(target) ?: return EntryCorrections.STALE.reply()
        val args = "${entry.id} ${entry.version}"
        return when (parts[0]) {
            "void" -> {
                result(corrections.change(target, EntryChange.Void, source(message, botId)), message.ownerId)
            }

            "entry" -> {
                entryReply(entry, message.ownerId)
            }

            "amount" -> {
                "Новая сумма в ${entry.originalAmount.currency}:\n/amount $args <сумма>".reply()
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

    fun command(
        message: InboxMessage,
        command: Command,
        botId: Long,
    ): TelegramReply? {
        require(access.owns(message.ownerId)) { "Budget access denied" }
        if (command.name !in COMMANDS) return null
        if (operations.owned(message.ownerId) == null) return "Сначала завершите настройку: /start.".reply()
        val parts = command.arguments.split(' ').filter { it.isNotEmpty() }
        return if (command.name in CATEGORY_COMMANDS) {
            categoryHandler.handle(message.ownerId, command.name, parts).reply()
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
            return if (change is EntryChange.Category && changed is CorrectionResult.Updated) {
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
        return EntryTarget(owner.householdId, owner.memberId, id, version)
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
