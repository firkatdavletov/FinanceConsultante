package com.firkatdavletov.financeconsultante.telegram.infrastructure

import com.firkatdavletov.financeconsultante.telegram.application.InboxMessage
import com.firkatdavletov.financeconsultante.telegram.application.InboxStore
import com.firkatdavletov.financeconsultante.telegram.application.IncomingKind
import com.firkatdavletov.financeconsultante.telegram.application.TelegramReply
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

open class JdbcInboxStore(
    private val jdbc: JdbcClient,
) : InboxStore {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun next(botId: Long): InboxMessage? {
        // Lock the bot before selecting the head; SKIP LOCKED on inbox alone could reorder one chat's answers.
        jdbc
            .sql("SELECT bot_id FROM telegram_polling_state WHERE bot_id = ? FOR UPDATE")
            .param(botId)
            .query(Long::class.java)
            .optional()
        return jdbc
            .sql(
                """
                SELECT * FROM telegram_inbox WHERE id = (
                SELECT id FROM telegram_inbox WHERE bot_id = ? AND status = 'RECEIVED' ORDER BY id LIMIT 1
                ) AND next_attempt_at <= clock_timestamp() FOR UPDATE
                """.trimIndent(),
            ).param(botId)
            .query {
                row,
                _,
                ->
                InboxMessage(
                    row.getLong("id"),
                    row.getLong("owner_id"),
                    row.getString("text"),
                    row.getObject("message_sent_at", OffsetDateTime::class.java)?.toInstant(),
                    IncomingKind.valueOf(row.getString("event_kind")),
                    row.getString("callback_query_id"),
                    row.getObject("telegram_message_id", Long::class.javaObjectType),
                )
            }.optional()
            .orElse(null)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun complete(
        message: InboxMessage,
        botId: Long,
        reply: String?,
    ) {
        if (reply != null) {
            jdbc
                .sql(
                    """
                    INSERT INTO telegram_outbox (inbox_id, bot_id, owner_id, text) VALUES (?, ?, ?, ?)
                    ON CONFLICT (inbox_id) DO NOTHING
                    """.trimIndent(),
                ).params(message.id, botId, message.ownerId, reply)
                .update()
        }
        jdbc
            .sql("UPDATE telegram_inbox SET status = ?, text = NULL WHERE id = ? AND bot_id = ?")
            .params(if (reply == null) "IGNORED" else "PROCESSED", message.id, botId)
            .update()
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun completeReply(
        message: InboxMessage,
        botId: Long,
        reply: TelegramReply,
    ) {
        complete(message, botId, reply.text)
        jdbc
            .sql(
                "UPDATE telegram_outbox SET buttons = :buttons::jsonb, callback_query_id = :query " +
                    "WHERE inbox_id = :inbox AND bot_id = :bot",
            ).param("buttons", ReplyButtonsJson.encode(reply.buttons))
            .param("query", message.callbackQueryId)
            .param("inbox", message.id)
            .param("bot", botId)
            .update()
    }

    @Transactional
    override fun failedAttempt(
        botId: Long,
        messageId: Long,
    ) {
        val message = next(botId)?.takeIf { it.id == messageId } ?: return
        jdbc
            .sql(
                """
                UPDATE telegram_inbox SET attempts = attempts + 1,
                status = CASE WHEN attempts >= 4 THEN 'FAILED' ELSE 'RECEIVED' END,
                text = CASE WHEN attempts >= 4 THEN NULL ELSE text END,
                next_attempt_at = clock_timestamp() + interval '30 seconds' WHERE id = ?
                """.trimIndent(),
            ).param(message.id)
            .update()
        jdbc
            .sql(
                """
                INSERT INTO telegram_outbox (inbox_id, bot_id, owner_id, text)
                SELECT id, bot_id, owner_id, 'Не удалось обработать сообщение. Повторите команду
                /history, затем повторите ввод; /draft — сохранённое уточнение.'
                FROM telegram_inbox WHERE id = ? AND status = 'FAILED' ON CONFLICT (inbox_id) DO
                NOTHING
                """.trimIndent(),
            ).param(message.id)
            .update()
    }
}
