package com.firkatdavletov.financeconsultante.telegram.application

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
) {
    private val conversation = SetupConversation()

    @Suppress("TooGenericExceptionCaught") // Roll back the exact inbox item before scheduling a retry.
    @Transactional
    open fun processOne(botId: Long): Boolean {
        val message = inbox.next(botId) ?: return false
        try {
            if (access.owns(message.ownerId)) {
                process(message, botId)
            } else {
                inbox.complete(message, botId, null)
            }
        } catch (failure: RuntimeException) {
            throw InboxProcessingFailure(message.id, failure)
        }
        return true
    }

    private fun process(
        message: InboxMessage,
        botId: Long,
    ) {
        val dialog = setup.dialog(message.ownerId)
        val current = setup.settings(message.ownerId)
        if (dialog.step == SetupStep.IDLE) {
            val reply = ledger.handle(message, botId)
            if (reply != null) {
                inbox.complete(message, botId, reply)
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
}

class TelegramDeliveryWorker(
    private val botId: Long,
    private val access: OwnerAccess,
    private val client: TelegramClient,
    private val outbox: OutboxStore,
) {
    fun deliverOne() {
        val delivery = outbox.claim(botId) ?: return
        if (!access.owns(delivery.ownerId)) {
            outbox.failed(delivery, TelegramFailure("access_revoked", permanent = true))
            return
        }
        try {
            outbox.sent(delivery, client.sendMessage(delivery.ownerId, delivery.text))
        } catch (failure: TelegramFailure) {
            outbox.failed(delivery, failure)
            logger.warn("Telegram reply delivery failed ({})", failure.code)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(TelegramDeliveryWorker::class.java)
    }
}
