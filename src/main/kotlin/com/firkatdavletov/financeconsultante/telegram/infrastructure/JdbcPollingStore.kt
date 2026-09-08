package com.firkatdavletov.financeconsultante.telegram.infrastructure

import com.firkatdavletov.financeconsultante.telegram.application.IncomingUpdate
import com.firkatdavletov.financeconsultante.telegram.application.OwnerAccess
import com.firkatdavletov.financeconsultante.telegram.application.PollLease
import com.firkatdavletov.financeconsultante.telegram.application.PollingStore
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
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
            val message = update.message
            val allowed =
                message != null && access.allows(message.userId, message.chatId, message.chatType, message.isBot) &&
                    !message.text.isNullOrBlank() && message.text.length <= RestTelegramClient.MAX_TEXT_LENGTH
            jdbc
                .sql(
                    """
                    INSERT INTO telegram_inbox (bot_id, update_id, owner_id, text, status)
                    VALUES (:bot, :update, :owner, :text, :status) ON CONFLICT (bot_id, update_id) DO NOTHING
                    """.trimIndent(),
                ).param("bot", lease.botId)
                .param("update", update.updateId)
                .param("owner", if (allowed) message.userId else null)
                .param("text", if (allowed) message.text else null)
                .param("status", if (allowed) "RECEIVED" else "IGNORED")
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
}
