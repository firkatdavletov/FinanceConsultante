package com.firkatdavletov.financeconsultante.telegram.infrastructure

import com.firkatdavletov.financeconsultante.telegram.application.IncomingKind
import com.firkatdavletov.financeconsultante.telegram.application.IncomingMessage
import com.firkatdavletov.financeconsultante.telegram.application.IncomingUpdate
import com.firkatdavletov.financeconsultante.telegram.application.OwnerAccess
import com.firkatdavletov.financeconsultante.telegram.application.PollLease
import com.firkatdavletov.financeconsultante.telegram.application.PollingStore
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID

open class JdbcPollingStore(
    private val jdbc: JdbcClient,
    private val leaseDuration: Duration,
) : PollingStore {
    @Transactional
    override fun claim(botId: Long): PollLease? {
        jdbc.sql("INSERT INTO telegram_polling_state (bot_id) VALUES (?) ON CONFLICT DO NOTHING").param(botId).update()
        val token = UUID.randomUUID()
        return jdbc
            .sql(
                """
                UPDATE telegram_polling_state SET lease_token = :token,
                lease_until = clock_timestamp() + :seconds * interval '1 second'
                WHERE bot_id = :bot AND next_attempt_at <= clock_timestamp()
                AND (lease_until IS NULL OR lease_until <= clock_timestamp())
                RETURNING next_offset
                """.trimIndent(),
            ).param("token", token)
            .param("seconds", leaseDuration.seconds)
            .param("bot", botId)
            .query { row, _ -> PollLease(botId, token, row.getLong("next_offset")) }
            .optional()
            .orElse(null)
    }

    @Transactional
    override fun accept(
        lease: PollLease,
        updates: List<IncomingUpdate>,
        access: OwnerAccess,
    ): Boolean {
        val active =
            jdbc
                .sql(
                    """
                    SELECT bot_id FROM telegram_polling_state WHERE bot_id = ? AND lease_token = ?
                    AND lease_until > clock_timestamp() FOR UPDATE
                    """.trimIndent(),
                ).params(lease.botId, lease.token)
                .query(Long::class.java)
                .optional()
                .isPresent
        if (!active) return false
        updates.sortedBy { it.updateId }.forEach { update ->
            require(update.updateId >= 0 && update.updateId < Long.MAX_VALUE)
            val message = authorized(update.message, access)
            jdbc
                .sql(
                    """
                    INSERT INTO telegram_inbox (bot_id, update_id, owner_id, text, status, message_sent_at,
                        event_kind, callback_query_id, telegram_message_id)
                    VALUES (:bot, :update, :owner, :text, :status, :sent, :kind, :query, :messageId) ON CONFLICT DO NOTHING
                    """.trimIndent(),
                ).param("bot", lease.botId)
                .param("update", update.updateId)
                .param("owner", message?.userId)
                .param("text", message?.text)
                .param("kind", message?.kind?.name ?: "MESSAGE")
                .param("query", message?.callbackQueryId)
                .param("messageId", message?.messageId)
                .param("status", if (message != null) "RECEIVED" else "IGNORED")
                .param("sent", message?.sentAt?.atOffset(ZoneOffset.UTC))
                .update()
        }
        // Derive from this returned batch: Telegram may choose a random update ID after a week of inactivity.
        val nextOffset = updates.maxOfOrNull { it.updateId + 1 } ?: lease.offset
        jdbc
            .sql(
                """
                UPDATE telegram_polling_state SET next_offset = ?, lease_token = NULL, lease_until = NULL,
                next_attempt_at = clock_timestamp() WHERE bot_id = ? AND lease_token = ?
                """.trimIndent(),
            ).params(nextOffset, lease.botId, lease.token)
            .update()
        return true
    }

    @Suppress("ReturnCount") // Discard unauthorized payload before persistence.
    private fun authorized(message: IncomingMessage?, access: OwnerAccess): IncomingMessage? {
        if (message == null ||
            !access.allows(message.userId, message.chatId, message.chatType, message.isBot)
        ) {
            return null
        }
        if (message.text.isNullOrBlank() || message.text.length > RestTelegramClient.MAX_TEXT_LENGTH) return null
        if (message.kind == IncomingKind.CALLBACK && !validCallback(message)) return null
        return if (message.kind == IncomingKind.EDITED) message.copy(text = "edited_message") else message
    }

    private fun validCallback(message: IncomingMessage): Boolean =
        message.callbackQueryId?.length in 1..MAX_QUERY_LENGTH &&
            message.text?.toByteArray(Charsets.UTF_8)?.size in 1..MAX_CALLBACK_BYTES

    @Transactional
    override fun retry(
        lease: PollLease,
        delay: Duration,
    ) {
        jdbc
            .sql(
                """
                UPDATE telegram_polling_state SET lease_token = NULL, lease_until = NULL,
                next_attempt_at = clock_timestamp() + :seconds * interval '1 second'
                WHERE bot_id = :bot AND lease_token = :token
                """.trimIndent(),
            ).param("seconds", delay.seconds)
            .param("bot", lease.botId)
            .param("token", lease.token)
            .update()
    }

    companion object {
        private const val MAX_QUERY_LENGTH = 256
        private const val MAX_CALLBACK_BYTES = 64
    }
}
