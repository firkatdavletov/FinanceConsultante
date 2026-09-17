package com.firkatdavletov.financeconsultante.telegram.application

import com.firkatdavletov.financeconsultante.access.application.FamilyBudgetAccess
import com.firkatdavletov.financeconsultante.access.domain.MemberRole
import com.firkatdavletov.financeconsultante.telegram.domain.BudgetSettings
import com.firkatdavletov.financeconsultante.telegram.domain.SetupDialog
import java.time.Duration
import java.time.Instant
import java.util.UUID

class OwnerAccess(
    private val ownerId: Long,
    private val families: FamilyBudgetAccess? = null,
) {
    init {
        require(ownerId > 0)
    }

    fun allows(
        userId: Long?,
        chatId: Long?,
        chatType: String?,
        isBot: Boolean,
    ): Boolean =
        userId != null && userId == chatId && chatType == "private" && !isBot &&
            (owns(userId) || families?.canReceive(userId) == true)

    fun owns(userId: Long): Boolean = userId == ownerId

    fun canProcess(
        userId: Long,
        memberId: UUID?,
    ): Boolean = owns(userId) || families?.canProcess(userId, memberId) == true

    fun canDeliver(
        userId: Long,
        memberId: UUID?,
        report: Boolean,
    ): Boolean =
        if (memberId == null) {
            owns(userId)
        } else {
            families?.canDeliver(userId, memberId, report) ?: owns(userId)
        }

    fun canConfigure(
        userId: Long,
        memberId: UUID?,
    ): Boolean = owns(userId) || memberId?.let { families?.activeMember(userId, it)?.role } == MemberRole.OWNER
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

data class BotCommand(
    val command: String,
    val description: String,
) {
    init {
        require(COMMAND_PATTERN.matches(command)) { "Invalid Telegram bot command" }
        require(description.length in 1..MAX_DESCRIPTION_LENGTH) { "Invalid Telegram bot command description" }
    }

    private companion object {
        val COMMAND_PATTERN = Regex("[a-z0-9_]{1,32}")
        const val MAX_DESCRIPTION_LENGTH = 256
    }
}

interface TelegramClient {
    fun getUpdates(offset: Long): List<IncomingUpdate>

    fun configureMenu(commands: List<BotCommand>)

    fun sendMessage(
        chatId: Long,
        text: String,
        buttons: List<ReplyButton> = emptyList(),
    ): Long

    fun editMessage(
        chatId: Long,
        messageId: Long,
        text: String,
        buttons: List<ReplyButton> = emptyList(),
    ): Long = throw UnsupportedOperationException("Editing Telegram messages is not supported by this client")

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
    val memberId: UUID? = null,
)

data class OutboxDelivery(
    val id: Long,
    val ownerId: Long,
    val text: String,
    val token: UUID,
    val buttons: List<ReplyButton> = emptyList(),
    val callbackQueryId: String? = null,
    val reportJobId: UUID? = null,
    val memberId: UUID? = null,
    val method: ReplyDelivery = ReplyDelivery.SEND,
    val targetMessageId: Long? = null,
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
