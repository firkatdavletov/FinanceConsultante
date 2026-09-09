package com.firkatdavletov.financeconsultante.telegram.application

import com.firkatdavletov.financeconsultante.telegram.domain.BudgetSettings
import com.firkatdavletov.financeconsultante.telegram.domain.SetupDialog
import java.time.Duration
import java.time.Instant
import java.util.UUID

class OwnerAccess(
    private val ownerId: Long,
) {
    init {
        require(ownerId > 0)
    }

    fun allows(
        userId: Long?,
        chatId: Long?,
        chatType: String?,
        isBot: Boolean,
    ): Boolean = userId == ownerId && chatId == ownerId && chatType == "private" && !isBot

    fun owns(userId: Long): Boolean = userId == ownerId
}

data class IncomingUpdate(
    val updateId: Long,
    val message: IncomingMessage? = null,
)

data class IncomingMessage(
    val userId: Long?,
    val chatId: Long?,
    val chatType: String?,
    val isBot: Boolean,
    val text: String?,
    val sentAt: Instant? = null,
    val kind: IncomingKind = IncomingKind.MESSAGE,
    val callbackQueryId: String? = null,
    val messageId: Long? = null,
)

enum class IncomingKind { MESSAGE, CALLBACK, EDITED }

interface TelegramClient {
    fun getUpdates(offset: Long): List<IncomingUpdate>

    fun sendMessage(
        chatId: Long,
        text: String,
        buttons: List<ReplyButton> = emptyList(),
    ): Long

    fun answerCallback(queryId: String)
}

class TelegramFailure(
    val code: String,
    val retryAfter: Duration = Duration.ofSeconds(DEFAULT_RETRY_SECONDS),
    val permanent: Boolean = false,
) : RuntimeException(code) {
    companion object {
        private const val DEFAULT_RETRY_SECONDS = 5L
    }
}

data class PollLease(
    val botId: Long,
    val token: UUID,
    val offset: Long,
)

data class InboxMessage(
    val id: Long,
    val ownerId: Long,
    val text: String,
    val sentAt: Instant? = null,
    val kind: IncomingKind = IncomingKind.MESSAGE,
    val callbackQueryId: String? = null,
    val messageId: Long? = null,
)

data class OutboxDelivery(
    val id: Long,
    val ownerId: Long,
    val text: String,
    val token: UUID,
    val buttons: List<ReplyButton> = emptyList(),
    val callbackQueryId: String? = null,
)

data class OwnedSettings(
    val settings: BudgetSettings,
    val hasEntries: Boolean,
)

interface PollingStore {
    fun claim(botId: Long): PollLease?

    fun accept(
        lease: PollLease,
        updates: List<IncomingUpdate>,
        access: OwnerAccess,
    ): Boolean

    fun retry(
        lease: PollLease,
        delay: Duration,
    )
}

interface InboxStore {
    /** Serializes processing for the bot in the caller's short transaction. */
    fun next(botId: Long): InboxMessage?

    fun complete(
        message: InboxMessage,
        botId: Long,
        reply: String?,
    )

    fun completeReply(
        message: InboxMessage,
        botId: Long,
        reply: TelegramReply,
    ) {
        complete(message, botId, reply.text)
    }

    fun failedAttempt(
        botId: Long,
        messageId: Long,
    )
}

interface SetupStore {
    fun dialog(ownerId: Long): SetupDialog

    fun settings(ownerId: Long): OwnedSettings?

    fun saveDialog(
        ownerId: Long,
        dialog: SetupDialog,
    )

    fun saveSettings(
        ownerId: Long,
        settings: BudgetSettings,
    )
}

interface OutboxStore {
    fun claim(botId: Long): OutboxDelivery?

    fun sent(
        delivery: OutboxDelivery,
        messageId: Long,
    )

    fun failed(
        delivery: OutboxDelivery,
        failure: TelegramFailure,
    )
}

class InboxProcessingFailure(
    val messageId: Long,
    cause: RuntimeException,
) : RuntimeException("inbox_processing", cause)
