package com.firkatdavletov.financeconsultante.telegram.application

import com.firkatdavletov.financeconsultante.analytics.application.AnalyticsMessages
import com.firkatdavletov.financeconsultante.analytics.application.AnalyticsReportService
import com.firkatdavletov.financeconsultante.analytics.domain.AnalyticsPeriod
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
    /** Consumes text required by an already active chat screen before optional AI routing. */
    fun conversationInput(message: InboxMessage): TelegramReply? = null

    /** null leaves a settings command to SetupConversation. */
    fun handle(
        message: InboxMessage,
        botId: Long,
    ): TelegramReply?

    /** An AI proposal is never posted directly; it becomes a versioned draft confirmed by its member. */
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
    private val goals: TelegramGoalsHandler? = null,
    private val budgetReports: BudgetReportService? = null,
    private val analyticsReports: AnalyticsReportService? = null,
    private val forecast: TelegramForecastHandler? = null,
    private val purchase: TelegramPurchaseHandler? = null,
    private val family: TelegramFamilyHandler? = null,
) : LedgerHandler {
    private val draftHandler = TelegramDraftHandler(operations, drafts)

    override fun conversationInput(message: InboxMessage): TelegramReply? =
        if (message.kind == IncomingKind.MESSAGE) corrections.categoryInput(message) else null

    override fun recognized(
        message: InboxMessage,
        botId: Long,
        proposal: RecordTransaction,
    ): TelegramReply {
        require(access.canProcess(message.ownerId, message.memberId)) { "Budget access denied" }
        val owner = operations.owned(message.ownerId) ?: return "Сначала завершите настройку: /start.".reply()
        val clarification = drafts.needsInput(owner, botId)
        val source = source(botId, clarification?.sourceInboxId ?: message.id)
        return when (val check = operations.check(owner, proposal, source)) {
            is ProposalCheck.Pending -> {
                saveDraft(owner, botId, message.id, clarification, proposal, check.state, check.reason)
            }

            is ProposalCheck.Ready -> {
                saveDraft(
                    owner,
                    botId,
                    message.id,
                    clarification,
                    proposal,
                    DraftState.READY,
                    "Распознано через OpenAI. Проверьте данные и подтвердите запись.",
                )
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    // Route commands and reject invalid transport data before proposing money.
    override fun handle(
        message: InboxMessage,
        botId: Long,
    ): TelegramReply? {
        require(access.canProcess(message.ownerId, message.memberId)) { "Budget access denied" }
        if (message.kind == IncomingKind.EDITED) {
            return "Изменение сообщения не меняет учёт. Откройте /history, выберите запись и исправьте её явно.".reply()
        }
        if (message.kind == IncomingKind.CALLBACK) {
            return if (message.text.substringBefore(':') in TelegramDraftHandler.CALLBACKS) {
                draftHandler.callback(message, botId)
            } else {
                corrections.callback(message, botId)
            }
        }
        conversationInput(message)?.let { return it }
        val sentAt = message.sentAt
        val today = (sentAt ?: clock.instant()).atZone(LedgerOperations.ZONE).toLocalDate()
        val initial = TransactionMessageParser().parse(message.text, today)
        if (initial is MessageParseResult.Command) {
            family?.command(message, initial, botId)?.let { return it }
            if (message.memberId == null && !access.owns(message.ownerId)) {
                return family?.pending(message.ownerId) ?: "Приглашение недоступно.".reply()
            }
            return routeCommand(message, initial, botId)
        }
        if (message.memberId == null && !access.owns(message.ownerId)) {
            return family?.pending(message.ownerId) ?: "Приглашение недоступно.".reply()
        }
        val owner = operations.owned(message.ownerId) ?: return "Сначала завершите настройку: /start.".reply()
        val parsed = TransactionMessageParser(categories.dictionary(message.ownerId)).parse(message.text, today)
        if (parsed == MessageParseResult.PurchaseQuestion) {
            return purchase?.question(message.text, owner, today)
                ?: "Анализ покупок временно недоступен. Используйте /purchase.".reply()
        }
        val clarification = drafts.needsInput(owner, botId)
        if (sentAt == null || sentAt > clock.instant()) {
            return saveDraft(
                owner,
                botId,
                message.id,
                clarification,
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
                propose(owner, botId, message.id, clarification, parsed)
            }

            is MessageParseResult.NeedsClarification -> {
                saveDraft(
                    owner,
                    botId,
                    message.id,
                    clarification,
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
        if (
            command.name !in COMMANDS && command.name !in TelegramRecurringHandler.COMMANDS &&
            command.name !in TelegramGoalsHandler.COMMANDS
        ) {
            return null
        }
        val owner =
            operations.owned(message.ownerId)
                ?: return if (command.name == "/today") {
                    BudgetMessages.incomplete().reply()
                } else {
                    "Сначала завершите настройку: /start.".reply()
                }
        recurring?.command(message, command, owner, botId)?.let { return it }
        goals?.command(message, command, owner, botId)?.let { return it }
        if (command.name == TelegramPurchaseHandler.COMMAND) {
            val today = (message.sentAt ?: clock.instant()).atZone(LedgerOperations.ZONE).toLocalDate()
            return purchase?.command(command.arguments, owner, today)
                ?: "Анализ покупок временно недоступен.".reply()
        }
        return command(command, owner, botId)
    }

    @Suppress("CyclomaticComplexMethod") // The command table keeps each independent Telegram route explicit.
    private fun command(
        command: MessageParseResult.Command,
        owner: OwnedLedger,
        botId: Long,
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

            "/report" -> {
                report(command.arguments, owner)
            }

            TelegramForecastHandler.COMMAND -> {
                forecast?.command(command.arguments, owner)
                    ?: "Прогноз временно недоступен.".reply()
            }

            "/history" -> {
                history(command.arguments, owner)
            }

            in TelegramDraftHandler.COMMANDS -> {
                draftHandler.command(command.name, command.arguments, owner, botId)
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
            LedgerMessages.history(operations.history(owner, before), owner)
        }
    }

    private fun report(
        arguments: String,
        owner: OwnedLedger,
    ): TelegramReply {
        val period =
            when (arguments) {
                "week" -> AnalyticsPeriod.WEEK
                "month" -> AnalyticsPeriod.MONTH
                else -> return "Формат: /report week или /report month.".reply()
            }
        return analyticsReports?.let { AnalyticsMessages.report(it.current(owner, period)).reply() }
            ?: "Аналитика временно недоступна.".reply()
    }

    private fun propose(
        owner: OwnedLedger,
        botId: Long,
        inboxId: Long,
        clarification: TransactionDraft?,
        proposal: RecordTransaction,
    ): TelegramReply {
        val check = operations.check(owner, proposal, source(botId, clarification?.sourceInboxId ?: inboxId))
        return when (check) {
            is ProposalCheck.Pending -> {
                saveDraft(owner, botId, inboxId, clarification, proposal, check.state, check.reason)
            }

            is ProposalCheck.Ready -> {
                if (clarification != null) {
                    saveDraft(
                        owner,
                        botId,
                        inboxId,
                        clarification,
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

    @Suppress("LongParameterList") // All fields belong to one draft transition.
    private fun saveDraft(
        owner: OwnedLedger,
        botId: Long,
        inboxId: Long,
        clarification: TransactionDraft?,
        proposal: RecordTransaction?,
        state: DraftState,
        reason: String,
    ): TelegramReply {
        val draft =
            clarification?.revise(proposal, state, reason)
                ?: TransactionDraft(UUID.randomUUID(), inboxId, state, 0, proposal, reason)
        drafts.save(owner, botId, draft)
        return LedgerMessages.draft(draft)
    }

    private fun source(
        botId: Long,
        inboxId: Long,
    ): String = "telegram:$botId:inbox:$inboxId"

    companion object {
        private val COMMANDS =
            setOf(
                "/balance",
                "/today",
                "/report",
                "/history",
                TelegramForecastHandler.COMMAND,
                TelegramPurchaseHandler.COMMAND,
            ) +
                TelegramDraftHandler.COMMANDS
    }
}
