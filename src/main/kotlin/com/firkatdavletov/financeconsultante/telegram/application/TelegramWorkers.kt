package com.firkatdavletov.financeconsultante.telegram.application

import com.firkatdavletov.financeconsultante.ai.application.OpenAiRecognitionRouter
import com.firkatdavletov.financeconsultante.telegram.domain.ACCOUNTING_ZONE
import com.firkatdavletov.financeconsultante.telegram.domain.SetupConversation
import com.firkatdavletov.financeconsultante.telegram.domain.SetupStep
import org.slf4j.LoggerFactory
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

class TelegramPoller(
    private val botId: Long,
    private val access: OwnerAccess,
    private val client: TelegramClient,
    private val store: PollingStore,
) {
    fun pollOnce() {
        val lease = store.claim(botId) ?: return
        try {
            store.accept(lease, client.getUpdates(lease.offset), access)
        } catch (failure: TelegramFailure) {
            store.retry(lease, failure.retryAfter)
            logger.warn("Telegram polling deferred ({})", failure.code)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(TelegramPoller::class.java)
    }
}

open class TelegramInboxProcessor(
    private val inbox: InboxStore,
    private val setup: SetupStore,
    private val access: OwnerAccess,
    private val clock: Clock,
    private val ledger: LedgerHandler,
    private val recognition: OpenAiRecognitionRouter? = null,
) {
    private val conversation = SetupConversation()

    @Suppress("TooGenericExceptionCaught") // Roll back the exact inbox item before scheduling a retry.
    @Transactional
    open fun processOne(botId: Long): Boolean {
        val message = inbox.next(botId) ?: return false
        try {
            if (access.canProcess(message.ownerId, message.memberId)) {
                process(message, botId)
            } else {
                inbox.complete(message, botId, null)
            }
        } catch (failure: RuntimeException) {
            throw InboxProcessingFailure(message.id, failure)
        }
        return true
    }

    @Suppress("ReturnCount") // Setup, member commands, and recognition each terminate the inbox exactly once.
    private fun process(
        message: InboxMessage,
        botId: Long,
    ) {
        if (!access.canConfigure(message.ownerId, message.memberId)) {
            processMember(message, botId)
            return
        }
        val dialog = setup.dialog(message.ownerId)
        val current = setup.settings(message.ownerId)
        if (dialog.step == SetupStep.IDLE || message.kind != IncomingKind.MESSAGE) {
            ledger.conversationInput(message, botId)?.let { reply ->
                inbox.completeReply(message, botId, reply)
                return
            }
            if (message.kind == IncomingKind.MESSAGE && recognition?.enqueueIfEligible(message, botId) == true) {
                return
            }
            val reply = ledger.handle(message, botId)
            if (reply != null) {
                inbox.completeReply(message, botId, reply)
                return
            }
        }
        val decision =
            conversation.respond(
                message.text,
                dialog,
                current?.settings,
                current?.hasEntries ?: false,
                LocalDate.now(clock.withZone(ACCOUNTING_ZONE)),
            )
        decision.save?.let { setup.saveSettings(message.ownerId, it) }
        setup.saveDialog(message.ownerId, decision.dialog)
        inbox.complete(message, botId, decision.reply)
    }

    private fun processMember(
        message: InboxMessage,
        botId: Long,
    ) {
        if (
            message.memberId != null && message.kind == IncomingKind.MESSAGE &&
            recognition?.enqueueIfEligible(message, botId) == true
        ) {
            return
        }
        val reply =
            if (message.kind == IncomingKind.MESSAGE && message.text.trim() == "/help") {
                MEMBER_HELP.reply()
            } else {
                ledger.handle(message, botId)
            }
                ?: (
                    "Команда доступна только OWNER. Доступ MEMBER: /family, /help, /balance, /history, " +
                        "/today, /report, /forecast, /purchase и /goals для просмотра."
                ).reply()
        inbox.completeReply(message, botId, reply)
    }

    private companion object {
        const val MEMBER_HELP =
            "Роль MEMBER: /family — участники и права; /balance, /history, /today, /report, /forecast, " +
                "/purchase, /recurring и /goals — общий бюджет. Добавляйте доходы и расходы обычным форматом; " +
                "исправлять, отменять и подтверждать можно только свои записи и черновики. " +
                "Настройки, участники, категории, планы и общие цели доступны только OWNER."
    }
}

class TelegramDeliveryWorker(
    private val botId: Long,
    private val access: OwnerAccess,
    private val client: TelegramClient,
    private val outbox: OutboxStore,
) {
    fun deliverOne() {
        val delivery = outbox.claim(botId) ?: return
        if (!access.canDeliver(delivery.ownerId, delivery.memberId, delivery.reportJobId != null)) {
            outbox.failed(delivery, TelegramFailure("access_revoked", permanent = true))
            return
        }
        delivery.callbackQueryId?.let { queryId ->
            try {
                client.answerCallback(queryId)
            } catch (failure: TelegramFailure) {
                // Expired callback acknowledgements must not suppress the durable financial response.
                logger.warn("Telegram callback acknowledgement failed ({})", failure.code)
            }
        }
        try {
            val messageId =
                when (delivery.method) {
                    ReplyDelivery.SEND -> {
                        client.sendMessage(delivery.ownerId, delivery.text, delivery.buttons)
                    }

                    ReplyDelivery.EDIT -> {
                        client.editMessage(
                            delivery.ownerId,
                            checkNotNull(delivery.targetMessageId),
                            delivery.text,
                            delivery.buttons,
                        )
                    }
                }
            outbox.sent(delivery, messageId)
        } catch (failure: TelegramFailure) {
            outbox.failed(delivery, failure)
            logger.warn("Telegram reply delivery failed ({})", failure.code)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(TelegramDeliveryWorker::class.java)
    }
}
