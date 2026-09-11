package com.firkatdavletov.financeconsultante.telegram.application

import com.firkatdavletov.financeconsultante.budget.application.BudgetMessages
import com.firkatdavletov.financeconsultante.budget.application.BudgetReportService
import com.firkatdavletov.financeconsultante.ledger.application.CategoryManagement
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

interface LedgerHandler {
    /** null leaves a settings command to SetupConversation. */
    fun handle(
        message: InboxMessage,
        botId: Long,
    ): TelegramReply?

    /** An AI proposal is never posted directly; it becomes a versioned owner-confirmed draft. */
    fun recognized(
        message: InboxMessage,
        botId: Long,
        proposal: RecordTransaction,
    ): TelegramReply
}

class TelegramLedgerHandler(
    private val access: OwnerAccess,
    private val operations: LedgerOperations,
    private val drafts: TransactionDraftRepository,
    private val clock: Clock,
    private val categories: CategoryManagement,
    private val corrections: TelegramCorrectionHandler,
    private val recurring: TelegramRecurringHandler? = null,
    private val budgetReports: BudgetReportService? = null,
) : LedgerHandler {
    override fun recognized(
        message: InboxMessage,
        botId: Long,
        proposal: RecordTransaction,
    ): TelegramReply {
        require(access.owns(message.ownerId)) { "Budget access denied" }
        val owner = operations.owned(message.ownerId) ?: return "Сначала завершите настройку: /start.".reply()
        val active = drafts.active(owner, botId)
        val source = source(botId, active?.sourceInboxId ?: message.id)
        return when (val check = operations.check(owner, proposal, source)) {
            is ProposalCheck.Pending -> {
                saveDraft(owner, botId, message.id, active, proposal, check.state, check.reason)
            }

            is ProposalCheck.Ready -> {
                saveDraft(
                    owner,
                    botId,
                    message.id,
                    active,
                    proposal,
                    DraftState.READY,
                    "Распознано через OpenAI. Проверьте данные и подтвердите запись.",
                )
            }
        }
    }

    @Suppress("ReturnCount") // Route commands and reject invalid transport data before proposing money.
    override fun handle(
        message: InboxMessage,
        botId: Long,
    ): TelegramReply? {
        require(access.owns(message.ownerId)) { "Budget access denied" }
        if (message.kind == IncomingKind.EDITED) {
            return "Изменение сообщения не меняет учёт. Откройте /history, выберите запись и исправьте её явно.".reply()
        }
        if (message.kind == IncomingKind.CALLBACK) return corrections.callback(message, botId)
        val sentAt = message.sentAt
        val today = (sentAt ?: clock.instant()).atZone(LedgerOperations.ZONE).toLocalDate()
        val initial = TransactionMessageParser().parse(message.text, today)
        if (initial is MessageParseResult.Command) return routeCommand(message, initial, botId)
        val owner = operations.owned(message.ownerId) ?: return "Сначала завершите настройку: /start.".reply()
        val parsed = TransactionMessageParser(categories.dictionary(message.ownerId)).parse(message.text, today)
        val active = drafts.active(owner, botId)
        if (parsed == MessageParseResult.PurchaseQuestion) {
            return (
                "Вопрос о покупке не записан как расход. Анализ покупок появится позже. " +
                    "Для фактической траты: /expense <сумма> <категория>." +
                    if (active != null) " Есть незавершённый черновик: /draft." else ""
            ).reply()
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
            is MessageParseResult.Command -> {
                routeCommand(message, parsed, botId)
            }

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

    @Suppress("ReturnCount") // Only ledger commands load the budget; setup keeps its existing route.
    private fun routeCommand(message: InboxMessage, command: MessageParseResult.Command, botId: Long): TelegramReply? {
        corrections.command(message, command, botId)?.let { return it }
        if (command.name !in COMMANDS && command.name !in TelegramRecurringHandler.COMMANDS) return null
        val owner =
            operations.owned(message.ownerId)
                ?: return if (command.name == "/today") {
                    BudgetMessages.incomplete().reply()
                } else {
                    "Сначала завершите настройку: /start.".reply()
                }
        recurring?.command(message, command, owner, botId)?.let { return it }
        return command(command, owner, botId, drafts.active(owner, botId))
    }

    private fun command(
        command: MessageParseResult.Command,
        owner: OwnedLedger,
        botId: Long,
        active: TransactionDraft?,
    ): TelegramReply? =
        when (command.name) {
            "/balance" -> {
                if (command.arguments.isEmpty()) {
                    LedgerMessages
                        .balance(
                            operations.balance(owner),
                        ).reply()
                } else {
                    "Формат: /balance".reply()
                }
            }

            "/today" -> {
                if (command.arguments.isEmpty()) {
                    budgetReports?.let { BudgetMessages.daily(it.today(owner)) }?.reply()
                        ?: "Оценка бюджета временно недоступна.".reply()
                } else {
                    "Формат: /today".reply()
                }
            }

            "/history" -> {
                history(command.arguments, owner)
            }

            "/draft" -> {
                active?.let(LedgerMessages::draft) ?: "Незавершённых черновиков нет.".reply()
            }

            "/confirm" -> {
                confirm(command.arguments, owner, botId)
            }

            "/cancel" -> {
                if (active == null) {
                    null
                } else {
                    drafts.save(owner, botId, active.cancel())
                    "Черновик отменён. Баланс не изменён.".reply()
                }
            }

            else -> {
                null
            }
        }

    private fun history(
        arguments: String,
        owner: OwnedLedger,
    ): TelegramReply {
        val before = if (arguments.isEmpty()) Long.MAX_VALUE else arguments.toLongOrNull()
        return if (before == null || before <= 0) {
            "Формат: /history или /history <курсор из ответа>.".reply()
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
    ): TelegramReply {
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
    ): TelegramReply {
        val match = CONFIRM.matchEntire(arguments) ?: return "Скопируйте команду подтверждения из /draft.".reply()
        val id = UUID.fromString(match.groupValues[1])
        val version = match.groupValues[2].toLongOrNull() ?: return "Некорректная версия. /draft".reply()
        val draft = drafts.find(owner, botId, id) ?: return "Черновик недоступен. /draft".reply()
        if (draft.state ==
            DraftState.CONFIRMED
        ) {
            return "Уже записано: ${draft.entryId}. Повторного изменения баланса нет.".reply()
        }
        if (!draft.active || draft.version != version) return "Подтверждение устарело. Откройте /draft.".reply()
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
    ): TelegramReply {
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
        private val COMMANDS = setOf("/balance", "/today", "/history", "/draft", "/confirm", "/cancel")
        private val CONFIRM = Regex("([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}) ([0-9]+)")
    }
}
