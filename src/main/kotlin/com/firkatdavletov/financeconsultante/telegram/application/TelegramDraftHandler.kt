package com.firkatdavletov.financeconsultante.telegram.application

import com.firkatdavletov.financeconsultante.ledger.application.LedgerOperations
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.ledger.application.ProposalCheck
import com.firkatdavletov.financeconsultante.ledger.application.TransactionDraftRepository
import com.firkatdavletov.financeconsultante.ledger.domain.DraftState
import com.firkatdavletov.financeconsultante.ledger.domain.TransactionDraft
import java.util.UUID

internal class TelegramDraftHandler(
    private val operations: LedgerOperations,
    private val drafts: TransactionDraftRepository,
) {
    fun command(
        name: String,
        arguments: String,
        owner: OwnedLedger,
        botId: Long,
    ): TelegramReply? {
        val active = drafts.active(owner, botId)
        return when (name) {
            "/draft" -> {
                val page = draftPage(arguments)
                if (page == null) {
                    "Формат: /draft [страница].".reply()
                } else {
                    LedgerMessages.drafts(active, page)
                }
            }

            "/confirm" -> {
                confirm(arguments, owner, botId, active)
            }

            else -> {
                cancel(arguments, owner, botId, active)
            }
        }
    }

    @Suppress("ReturnCount") // Callback guards reject malformed, stale, or inaccessible draft actions.
    fun callback(
        message: InboxMessage,
        botId: Long,
    ): TelegramReply {
        val owner = operations.owned(message.ownerId) ?: return "Сначала завершите настройку: /start.".reply()
        val match = DRAFT_CALLBACK.matchEntire(message.text) ?: return "Кнопка черновика недоступна. /draft".reply()
        val id = UUID.fromString(match.groupValues[2])
        val version = match.groupValues[3].toLongOrNull() ?: return "Кнопка черновика недоступна. /draft".reply()
        return when (match.groupValues[1]) {
            "dc" -> confirm(owner, botId, id, version)
            "dx" -> cancel(owner, botId, id, version)
            else -> show(owner, botId, id, version)
        }
    }

    @Suppress("ReturnCount") // The no-argument shortcut is valid only for one unambiguous active draft.
    private fun confirm(
        arguments: String,
        owner: OwnedLedger,
        botId: Long,
        active: List<TransactionDraft>,
    ): TelegramReply {
        if (arguments.isEmpty()) {
            return when (active.size) {
                0 -> "Незавершённых черновиков нет.".reply()
                1 -> confirm(owner, botId, active.single().id, active.single().version)
                else -> LedgerMessages.drafts(active, notice = "Выберите черновик кнопкой «Записать».")
            }
        }
        val target =
            draftTarget(arguments)
                ?: return LedgerMessages.drafts(active, notice = "Не удалось распознать подтверждение.")
        return confirm(owner, botId, target.first, target.second)
    }

    @Suppress("ReturnCount") // Every terminal state protects the ledger from a stale confirmation.
    private fun confirm(
        owner: OwnedLedger,
        botId: Long,
        id: UUID,
        version: Long,
    ): TelegramReply {
        val draft = drafts.find(owner, botId, id) ?: return "Черновик недоступен. /draft".reply()
        if (draft.state == DraftState.CONFIRMED) {
            return "Уже записано: ${draft.entryId}. Повторного изменения баланса нет.".reply()
        }
        if (draft.state == DraftState.CANCELLED) return "Черновик уже отменён. Баланс не изменён.".reply()
        if (draft.version != version) return LedgerMessages.draft(draft, "Подтверждение устарело. Актуальные данные:")
        if (draft.state == DraftState.NEEDS_INPUT) {
            return LedgerMessages.draft(draft, "Черновик нужно уточнить перед записью.")
        }
        val proposal = checkNotNull(draft.proposal)
        return when (val checked = operations.check(owner, proposal, source(botId, draft.sourceInboxId))) {
            is ProposalCheck.Pending -> {
                val revised = draft.revise(proposal, checked.state, checked.reason)
                drafts.save(owner, botId, revised)
                LedgerMessages.draft(revised)
            }

            is ProposalCheck.Ready -> {
                val entry = operations.record(checked).entry
                drafts.save(owner, botId, draft.confirm(entry.id))
                LedgerMessages.recorded(entry, proposal.category.displayName)
            }
        }
    }

    @Suppress("ReturnCount") // A specific target is required when more than one draft is active.
    private fun cancel(
        arguments: String,
        owner: OwnedLedger,
        botId: Long,
        active: List<TransactionDraft>,
    ): TelegramReply? {
        if (arguments.isEmpty()) {
            return when (active.size) {
                0 -> null
                1 -> cancel(owner, botId, active.single().id, active.single().version)
                else -> LedgerMessages.drafts(active, notice = "Выберите черновик кнопкой «Отменить».")
            }
        }
        val target =
            draftTarget(arguments)
                ?: return LedgerMessages.drafts(active, notice = "Не удалось распознать отмену.")
        return cancel(owner, botId, target.first, target.second)
    }

    @Suppress("ReturnCount") // Terminal and stale states must remain idempotent.
    private fun cancel(
        owner: OwnedLedger,
        botId: Long,
        id: UUID,
        version: Long,
    ): TelegramReply {
        val draft = drafts.find(owner, botId, id) ?: return "Черновик недоступен. /draft".reply()
        if (draft.state == DraftState.CONFIRMED) {
            return "Уже записано: ${draft.entryId}. Для отмены записи используйте /history.".reply()
        }
        if (draft.state == DraftState.CANCELLED) return "Черновик уже отменён. Баланс не изменён.".reply()
        if (draft.version != version) return LedgerMessages.draft(draft, "Отмена устарела. Актуальные данные:")
        drafts.save(owner, botId, draft.cancel())
        return "Черновик отменён. Баланс не изменён. Остальные черновики: /draft".reply()
    }

    @Suppress("ReturnCount") // The selected callback version is checked before prompting for clarification.
    private fun show(
        owner: OwnedLedger,
        botId: Long,
        id: UUID,
        version: Long,
    ): TelegramReply {
        val draft = drafts.find(owner, botId, id) ?: return "Черновик недоступен. /draft".reply()
        if (!draft.active) return "Черновик уже завершён. Откройте /draft или /history.".reply()
        if (draft.version != version) return LedgerMessages.draft(draft, "Кнопка устарела. Актуальные данные:")
        if (draft.state == DraftState.NEEDS_INPUT) {
            return LedgerMessages.draft(draft, "Отправьте одну исправленную операцию целиком.")
        }
        drafts.needsInput(owner, botId)?.let {
            return LedgerMessages.draft(it, "Сначала завершите текущее уточнение.")
        }
        val selected =
            draft.revise(
                draft.proposal,
                DraftState.NEEDS_INPUT,
                "Отправьте одну исправленную операцию целиком.",
            )
        drafts.save(owner, botId, selected)
        return LedgerMessages.draft(selected, "Выбран черновик для уточнения.")
    }

    @Suppress("ReturnCount") // Both UUID and optimistic-lock version are mandatory for a text fallback.
    private fun draftTarget(arguments: String): Pair<UUID, Long>? {
        val match = DRAFT_TARGET.matchEntire(arguments) ?: return null
        val version = match.groupValues[2].toLongOrNull() ?: return null
        return UUID.fromString(match.groupValues[1]) to version
    }

    private fun draftPage(arguments: String): Int? =
        if (arguments.isEmpty()) 1 else arguments.toIntOrNull()?.takeIf { it > 0 }

    private fun source(
        botId: Long,
        inboxId: Long,
    ): String = "telegram:$botId:inbox:$inboxId"

    companion object {
        val COMMANDS = setOf("/draft", "/confirm", "/cancel")
        val CALLBACKS = setOf("dc", "dx", "du")
        private const val UUID_PATTERN = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
        private val DRAFT_TARGET = Regex("($UUID_PATTERN) ([0-9]+)")
        private val DRAFT_CALLBACK = Regex("(dc|dx|du):($UUID_PATTERN):([0-9]+)")
    }
}
