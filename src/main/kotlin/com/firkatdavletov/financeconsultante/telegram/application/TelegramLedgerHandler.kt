package com.firkatdavletov.financeconsultante.telegram.application

import com.firkatdavletov.financeconsultante.ledger.application.LedgerOperations
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.ledger.application.ProposalCheck
import com.firkatdavletov.financeconsultante.ledger.application.TransactionDraftRepository
import com.firkatdavletov.financeconsultante.ledger.domain.DraftState
import com.firkatdavletov.financeconsultante.ledger.domain.MessageParseResult
import com.firkatdavletov.financeconsultante.ledger.domain.MessageParseResult.RecordTransaction
import com.firkatdavletov.financeconsultante.ledger.domain.TransactionDraft
import com.firkatdavletov.financeconsultante.ledger.domain.TransactionMessageParser
import java.time.Clock
import java.util.UUID

fun interface LedgerHandler {
    /** null leaves a settings command to SetupConversation. */
    fun handle(
        message: InboxMessage,
        botId: Long,
    ): String?
}

class TelegramLedgerHandler(
    private val access: OwnerAccess,
    private val operations: LedgerOperations,
    private val drafts: TransactionDraftRepository,
    private val clock: Clock,
) : LedgerHandler {
    private val parser = TransactionMessageParser()

    @Suppress("ReturnCount") // Route commands and reject invalid transport data before proposing money.
    override fun handle(
        message: InboxMessage,
        botId: Long,
    ): String? {
        require(access.owns(message.ownerId)) { "Budget access denied" }
        val sentAt = message.sentAt
        val today = (sentAt ?: clock.instant()).atZone(LedgerOperations.ZONE).toLocalDate()
        val parsed = parser.parse(message.text, today)
        if (parsed is MessageParseResult.Command && parsed.name !in COMMANDS) return null
        val owner = operations.owned(message.ownerId) ?: return "Сначала завершите настройку: /start."
        val active = drafts.active(owner, botId)
        if (parsed is MessageParseResult.Command) return command(parsed, owner, botId, active)
        if (parsed == MessageParseResult.PurchaseQuestion) {
            return "Вопрос о покупке не записан как расход. Анализ покупок появится позже. " +
                "Для фактической траты: /expense <сумма> <категория>." +
                if (active != null) " Есть незавершённый черновик: /draft." else ""
        }
        if (sentAt == null || sentAt > clock.instant()) {
            return saveDraft(
                owner,
                botId,
                message.id,
                active,
                null,
                DraftState.NEEDS_INPUT,
                "Дата исходного сообщения неизвестна или ещё не наступила. Отправьте операцию заново с верной датой.",
            )
        }
        return when (parsed) {
            is RecordTransaction -> {
                propose(owner, botId, message.id, active, parsed)
            }

            is MessageParseResult.NeedsClarification -> {
                saveDraft(
                    owner,
                    botId,
                    message.id,
                    active,
                    null,
                    DraftState.NEEDS_INPUT,
                    parsed.reason.message,
                )
            }
        }
    }

    private fun command(
        command: MessageParseResult.Command,
        owner: OwnedLedger,
        botId: Long,
        active: TransactionDraft?,
    ): String? =
        when (command.name) {
            "/balance" -> {
                if (command.arguments.isEmpty()) {
                    LedgerMessages.balance(
                        operations.balance(owner),
                    )
                } else {
                    "Формат: /balance"
                }
            }

            "/history" -> {
                history(command.arguments, owner)
            }

            "/draft" -> {
                active?.let(LedgerMessages::draft) ?: "Незавершённых черновиков нет."
            }

            "/confirm" -> {
                confirm(command.arguments, owner, botId)
            }

            "/cancel" -> {
                if (active == null) {
                    null
                } else {
                    drafts.save(owner, botId, active.cancel())
                    "Черновик отменён. Баланс не изменён."
                }
            }

            else -> {
                null
            }
        }

    private fun history(
        arguments: String,
        owner: OwnedLedger,
    ): String {
        val before = if (arguments.isEmpty()) Long.MAX_VALUE else arguments.toLongOrNull()
        return if (before == null || before <= 0) {
            "Формат: /history или /history <курсор из ответа>."
        } else {
            LedgerMessages.history(operations.history(owner, before))
        }
    }

    private fun propose(
        owner: OwnedLedger,
        botId: Long,
        inboxId: Long,
        active: TransactionDraft?,
        proposal: RecordTransaction,
    ): String {
        val check = operations.check(owner, proposal, source(botId, active?.sourceInboxId ?: inboxId))
        return when (check) {
            is ProposalCheck.Pending -> {
                saveDraft(owner, botId, inboxId, active, proposal, check.state, check.reason)
            }

            is ProposalCheck.Ready -> {
                if (active != null) {
                    saveDraft(
                        owner,
                        botId,
                        inboxId,
                        active,
                        proposal,
                        DraftState.READY,
                        "Проверьте данные и подтвердите запись.",
                    )
                } else {
                    LedgerMessages.recorded(operations.record(check).entry, proposal.category.displayName)
                }
            }
        }
    }

    @Suppress("ReturnCount") // Each guard rejects a distinct stale or unauthorized confirmation.
    private fun confirm(
        arguments: String,
        owner: OwnedLedger,
        botId: Long,
    ): String {
        val match = CONFIRM.matchEntire(arguments) ?: return "Скопируйте команду подтверждения из /draft."
        val id = UUID.fromString(match.groupValues[1])
        val version = match.groupValues[2].toLongOrNull() ?: return "Некорректная версия. /draft"
        val draft = drafts.find(owner, botId, id) ?: return "Черновик недоступен. /draft"
        if (draft.state ==
            DraftState.CONFIRMED
        ) {
            return "Уже записано: ${draft.entryId}. Повторного изменения баланса нет."
        }
        if (!draft.active || draft.version != version) return "Подтверждение устарело. Откройте /draft."
        if (draft.state == DraftState.NEEDS_INPUT) return LedgerMessages.draft(draft)
        val proposal = checkNotNull(draft.proposal)
        return when (val checked = operations.check(owner, proposal, source(botId, draft.sourceInboxId))) {
            is ProposalCheck.Pending -> {
                saveDraft(
                    owner,
                    botId,
                    draft.sourceInboxId,
                    draft,
                    proposal,
                    checked.state,
                    checked.reason,
                )
            }

            is ProposalCheck.Ready -> {
                val entry = operations.record(checked).entry
                drafts.save(owner, botId, draft.confirm(entry.id))
                LedgerMessages.recorded(entry, proposal.category.displayName)
            }
        }
    }

    @Suppress("LongParameterList") // All fields belong to one draft transition.
    private fun saveDraft(
        owner: OwnedLedger,
        botId: Long,
        inboxId: Long,
        active: TransactionDraft?,
        proposal: RecordTransaction?,
        state: DraftState,
        reason: String,
    ): String {
        val draft =
            active?.revise(proposal, state, reason)
                ?: TransactionDraft(UUID.randomUUID(), inboxId, state, 0, proposal, reason)
        drafts.save(owner, botId, draft)
        return LedgerMessages.draft(draft)
    }

    private fun source(
        botId: Long,
        inboxId: Long,
    ): String = "telegram:$botId:inbox:$inboxId"

    companion object {
        private val COMMANDS = setOf("/balance", "/history", "/draft", "/confirm", "/cancel")
        private val CONFIRM = Regex("([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}) ([0-9]+)")
    }
}
