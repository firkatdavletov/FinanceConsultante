package com.firkatdavletov.financeconsultante.telegram.application

import com.firkatdavletov.financeconsultante.ledger.application.HistoryItem
import com.firkatdavletov.financeconsultante.ledger.application.LedgerOperations
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.ledger.domain.Balance
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.ledger.domain.DraftState
import com.firkatdavletov.financeconsultante.ledger.domain.EntryStatus
import com.firkatdavletov.financeconsultante.ledger.domain.LedgerEntry
import com.firkatdavletov.financeconsultante.ledger.domain.TransactionDraft
import com.firkatdavletov.financeconsultante.shared.domain.Money
import com.firkatdavletov.financeconsultante.telegram.domain.ACCOUNTING_ZONE

object LedgerMessages {
    const val DRAFT_PAGE_SIZE = 5

    fun balance(balance: Balance): String =
        "Учётный баланс: ${money(balance.amount)}" +
            (if (balance.amount.amount.signum() < 0) " (отрицательный)" else "") +
            ".\nДоходы: ${money(balance.income)}. Расходы: ${money(balance.expenses)}. " +
            "Возвраты: ${money(
                balance.refunds,
            )}. Чистые расходы: ${money(
                balance.netExpenses,
            )}.\nНачальный остаток не включён в доходы; черновики не учтены."

    fun recorded(
        entry: LedgerEntry,
        category: String,
    ): TelegramReply =
        TelegramReply(
            "Записано: ${line(entry, category)}\nОперация ${entry.id}. /balance — баланс; /history — история.",
            EntryButtons.actions(entry),
        )

    fun draft(
        draft: TransactionDraft,
        notice: String? = null,
    ): TelegramReply =
        TelegramReply(
            buildString {
                notice?.let { append("$it\n") }
                append(draftText(draft))
                append("\nБаланс не изменён.")
            },
            draftButtons(draft),
        )

    @Suppress("ReturnCount") // Empty and invalid pages are distinct user recovery responses.
    fun drafts(
        drafts: List<TransactionDraft>,
        page: Int = 1,
        notice: String? = null,
    ): TelegramReply {
        if (drafts.isEmpty()) return "Незавершённых черновиков нет.".reply()
        val pages = (drafts.size + DRAFT_PAGE_SIZE - 1) / DRAFT_PAGE_SIZE
        if (page !in 1..pages) return "Страница черновиков недоступна. Формат: /draft [страница].".reply()
        val offset = (page - 1) * DRAFT_PAGE_SIZE
        val items = drafts.drop(offset).take(DRAFT_PAGE_SIZE)
        val text =
            buildString {
                notice?.let { append("$it\n") }
                append("Незавершённые черновики: ${drafts.size}. Баланс не изменён.\n")
                items.forEachIndexed { index, draft ->
                    append("\n${offset + index + 1}. ")
                    append(draftText(draft))
                    append("\n")
                }
                append("\nСтраница $page из $pages.")
                if (page < pages) append(" Далее: /draft ${page + 1}")
            }
        return TelegramReply(
            text,
            items.flatMapIndexed { index, draft -> draftButtons(draft, offset + index + 1) },
        )
    }

    private fun draftText(draft: TransactionDraft): String =
        buildString {
            append("Черновик ${draft.id}, версия ${draft.version}.\n")
            draft.proposal?.let {
                append("${it.occurredOn}: ${it.category.displayName}, ${money(it.amount)}.\n")
            }
            append(draft.reason)
            if (draft.state == DraftState.READY || draft.state == DraftState.WAITING_RATE) {
                append("\nНажмите «Записать» или используйте: /confirm ${draft.id} ${draft.version}")
            } else {
                append("\nНажмите «Уточнить» и отправьте одну операцию целиком.")
            }
            append("\nОтменить этот черновик: /cancel ${draft.id} ${draft.version}")
        }

    private fun draftButtons(
        draft: TransactionDraft,
        number: Int? = null,
    ): List<ReplyButton> {
        val suffix = "${draft.id}:${draft.version}"
        val label = number?.let { " $it" } ?: ""
        val actions = mutableListOf<ReplyButton>()
        if (draft.state != DraftState.NEEDS_INPUT) {
            actions += ReplyButton("✅ Записать$label", "dc:$suffix")
        }
        actions += ReplyButton("✏️ Уточнить$label", "du:$suffix")
        actions += ReplyButton("❌ Отменить$label", "dx:$suffix")
        return actions
    }

    fun history(
        items: List<HistoryItem>,
        requester: OwnedLedger? = null,
    ): TelegramReply {
        if (items.isEmpty()) return "История пуста. /history — с начала.".reply()
        val page = items.take(LedgerOperations.HISTORY_PAGE_SIZE)
        val text =
            "Последние записи (по порядку добавления):\n" +
                page.joinToString("\n") {
                    "${it.sequence}. ${line(it.entry, it.category)}" +
                        (it.actorTelegramUserId?.let { actor -> " Автор: Telegram ID $actor." } ?: "")
                } +
                if (items.size > page.size) "\nДалее: /history ${page.last().sequence}" else "\nКонец истории."
        return TelegramReply(
            text,
            page
                .filter {
                    it.entry.status == EntryStatus.POSTED &&
                        (requester == null || requester.canManageHousehold || it.entry.actorId == requester.memberId)
                }.map {
                    ReplyButton("Запись ${it.sequence}", "entry:${it.entry.id}:${it.entry.version}")
                },
        )
    }

    internal fun line(
        entry: LedgerEntry,
        category: String,
    ): String {
        val direction =
            when (entry.direction) {
                Direction.INCOME -> "доход"
                Direction.EXPENSE -> "расход"
                Direction.EXPENSE_REFUND -> "возврат"
            }
        val converted =
            entry.exchangeRate?.let {
                " = ${money(entry.amountKzt)} (курс ${it.rubKzt.toPlainString()})"
            } ?: ""
        val status = if (entry.status == EntryStatus.VOIDED) " [отменена]" else ""
        return "${entry.occurredAt.atZone(ACCOUNTING_ZONE).toLocalDate()} $direction, $category: " +
            money(entry.originalAmount) + converted + status
    }

    private fun money(value: Money): String = "${value.amount.toPlainString()} ${value.currency}"
}
