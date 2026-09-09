package com.firkatdavletov.financeconsultante.telegram.application

import com.firkatdavletov.financeconsultante.ledger.application.HistoryItem
import com.firkatdavletov.financeconsultante.ledger.application.LedgerOperations
import com.firkatdavletov.financeconsultante.ledger.domain.Balance
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.ledger.domain.DraftState
import com.firkatdavletov.financeconsultante.ledger.domain.EntryStatus
import com.firkatdavletov.financeconsultante.ledger.domain.LedgerEntry
import com.firkatdavletov.financeconsultante.ledger.domain.TransactionDraft
import com.firkatdavletov.financeconsultante.shared.domain.Money
import com.firkatdavletov.financeconsultante.telegram.domain.ACCOUNTING_ZONE

object LedgerMessages {
    fun balance(balance: Balance): String =
        "Учётный баланс: ${money(balance.amount)}" +
            (if (balance.amount.amount.signum() < 0) " (отрицательный)" else "") +
            ".\nДоходы: ${money(balance.income)}. Расходы: ${money(balance.expenses)}. " +
            "Возвраты: ${money(balance.refunds)}.\nНачальный остаток не включён в доходы; черновики не учтены."

    fun recorded(
        entry: LedgerEntry,
        category: String,
    ): String = "Записано: ${line(entry, category)}\nОперация ${entry.id}. /balance — баланс; /history — история."

    fun draft(draft: TransactionDraft): String =
        buildString {
            append("Черновик ${draft.id}, версия ${draft.version}. Баланс не изменён.\n")
            draft.proposal?.let {
                append("${it.occurredOn}: ${it.category.displayName}, ${money(it.amount)}.\n")
            }
            append(draft.reason)
            if (draft.state == DraftState.READY || draft.state == DraftState.WAITING_RATE) {
                append("\n/confirm ${draft.id} ${draft.version}")
            }
            append("\nДля уточнения отправьте одну операцию целиком. /draft — показать; /cancel — отменить черновик.")
        }

    fun history(items: List<HistoryItem>): String {
        if (items.isEmpty()) return "История пуста. /history — с начала."
        val page = items.take(LedgerOperations.HISTORY_PAGE_SIZE)
        return "Последние записи (по порядку добавления):\n" +
            page.joinToString("\n") { "${it.sequence}. ${line(it.entry, it.category)}" } +
            if (items.size > page.size) "\nДалее: /history ${page.last().sequence}" else "\nКонец истории."
    }

    private fun line(
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
