package com.firkatdavletov.financeconsultante.telegram.infrastructure

import com.firkatdavletov.financeconsultante.telegram.application.OutboxDelivery
import com.firkatdavletov.financeconsultante.telegram.application.OutboxStore
import com.firkatdavletov.financeconsultante.telegram.application.TelegramFailure
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID

open class JdbcOutboxStore(
    private val jdbc: JdbcClient,
    private val leaseDuration: Duration,
) : OutboxStore {
    @Transactional
    override fun claim(botId: Long): OutboxDelivery? {
        jdbc
            .sql(
                """
                WITH exhausted AS (
                    UPDATE telegram_outbox SET status = 'FAILED', lease_token = NULL, lease_until = NULL,
                        last_error = 'delivery_exhausted'
                    WHERE bot_id = ? AND attempts >= 5 AND status = 'SENDING'
                        AND lease_until <= clock_timestamp()
                    RETURNING report_job_id
                )
                UPDATE daily_report_job job SET status = 'FAILED', updated_at = clock_timestamp()
                FROM exhausted WHERE job.id = exhausted.report_job_id AND job.status = 'QUEUED'
                """.trimIndent(),
            ).param(botId)
            .update()
        val token = UUID.randomUUID()
        return jdbc
            .sql(
                """
                UPDATE telegram_outbox SET status = 'SENDING', attempts = attempts + 1,
                lease_token = :token, lease_until = clock_timestamp() + :seconds * interval '1 second'
                WHERE id = (
                SELECT id FROM telegram_outbox WHERE bot_id = :bot AND status IN ('PENDING', 'SENDING')
                ORDER BY id LIMIT 1
                ) AND next_attempt_at <= clock_timestamp()
                AND (status = 'PENDING' OR lease_until <= clock_timestamp())
                RETURNING id, owner_id, text, buttons, callback_query_id, report_job_id, member_id,
                    delivery_method, target_message_id
                """.trimIndent(),
            ).param("bot", botId)
            .param("token", token)
            .param("seconds", leaseDuration.seconds)
            .query {
                row,
                _,
                ->
                OutboxDelivery(
                    row.getLong("id"),
                    row.getLong("owner_id"),
                    row.getString("text"),
                    token,
                    ReplyButtonsJson.decode(row.getString("buttons")),
                    row.getString("callback_query_id"),
                    row.getObject("report_job_id", UUID::class.java),
                    row.getObject("member_id", UUID::class.java),
                    com.firkatdavletov.financeconsultante.telegram.application.ReplyDelivery.valueOf(
                        row.getString("delivery_method"),
                    ),
                    row.getObject("target_message_id", Long::class.javaObjectType),
                )
            }.optional()
            .orElse(null)
    }

    @Transactional
    override fun sent(
        delivery: OutboxDelivery,
        messageId: Long,
    ) {
        val updated =
            jdbc
                .sql(
                    """
                    UPDATE telegram_outbox SET status = 'SENT', telegram_message_id = ?, lease_token = NULL,
                    lease_until = NULL, last_error = NULL WHERE id = ? AND lease_token = ?
                    """.trimIndent(),
                ).params(messageId, delivery.id, delivery.token)
                .update()
        if (updated == 1 && delivery.reportJobId != null) {
            jdbc
                .sql(
                    """
                    UPDATE daily_report_job SET status = 'SENT', updated_at = clock_timestamp()
                    WHERE id = ? AND status = 'QUEUED'
                    """.trimIndent(),
                ).param(delivery.reportJobId)
                .update()
        }
    }

    @Transactional
    override fun failed(
        delivery: OutboxDelivery,
        failure: TelegramFailure,
    ) {
        val updated =
            jdbc
                .sql(
                    """
                    UPDATE telegram_outbox SET status = CASE WHEN :permanent OR attempts >= 5 THEN
                    'FAILED' ELSE 'PENDING' END,
                    next_attempt_at = clock_timestamp() + :seconds * interval '1 second',
                    lease_token = NULL, lease_until = NULL, last_error = :error
                    WHERE id = :id AND lease_token = :token
                    """.trimIndent(),
                ).param("permanent", failure.permanent)
                .param("seconds", failure.retryAfter.seconds)
                .param("error", failure.code)
                .param("id", delivery.id)
                .param("token", delivery.token)
                .update()
        if (updated == 1 && delivery.reportJobId != null) {
            jdbc
                .sql(
                    """
                    UPDATE daily_report_job job SET status = 'FAILED', updated_at = clock_timestamp()
                    FROM telegram_outbox outbox
                    WHERE job.id = ? AND job.status = 'QUEUED'
                        AND outbox.id = ? AND outbox.status = 'FAILED'
                    """.trimIndent(),
                ).params(delivery.reportJobId, delivery.id)
                .update()
        }
    }
}
