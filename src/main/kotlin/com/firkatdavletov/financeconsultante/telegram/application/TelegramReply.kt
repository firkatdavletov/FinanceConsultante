package com.firkatdavletov.financeconsultante.telegram.application

import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.ledger.domain.EntryStatus
import com.firkatdavletov.financeconsultante.ledger.domain.LedgerEntry

data class ReplyButton(
    val text: String,
    val data: String,
    val row: Int? = null,
)

enum class ReplyDelivery {
    SEND,
    EDIT,
}

data class TelegramReply(
    val text: String,
    val buttons: List<ReplyButton> = emptyList(),
    val delivery: ReplyDelivery = ReplyDelivery.SEND,
    val targetMessageId: Long? = null,
) {
    init {
        require((delivery == ReplyDelivery.EDIT) == (targetMessageId != null))
    }

    fun edit(messageId: Long): TelegramReply = copy(delivery = ReplyDelivery.EDIT, targetMessageId = messageId)
}

fun String.reply() = TelegramReply(this)

object EntryButtons {
    fun actions(entry: LedgerEntry): List<ReplyButton> {
        if (entry.status != EntryStatus.POSTED) return emptyList()
        val suffix = "${entry.id}:${entry.version}"
        return listOf(
            ReplyButton("Сумма", "amount:$suffix"),
            ReplyButton("Категория", "cat:$suffix"),
            ReplyButton("Дата", "date:$suffix"),
            ReplyButton("Отменить запись", "void:$suffix"),
        ) +
            if (entry.direction == Direction.EXPENSE) listOf(ReplyButton("Возврат", "refund:$suffix")) else emptyList()
    }
}
