package com.firkatdavletov.financeconsultante.ai.infrastructure

import com.firkatdavletov.financeconsultante.ai.application.RecognitionCompletion
import com.firkatdavletov.financeconsultante.ai.application.RecognitionJob
import com.firkatdavletov.financeconsultante.ai.application.RecognitionJobRepository
import com.firkatdavletov.financeconsultante.ai.domain.RecognitionCategory
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.telegram.application.InboxMessage
import com.firkatdavletov.financeconsultante.telegram.application.IncomingKind
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JdbcRecognitionJobRepository(
    private val jdbc: JdbcClient,
) : RecognitionJobRepository {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun enqueue(
        message: InboxMessage,
        botId: Long,
        modelId: String,
        promptVersion: String,
        schemaVersion: String,
    ): Boolean {
        val inserted =
            jdbc
                .sql(
                    """
                    INSERT INTO openai_recognition_job (
                        id, inbox_id, bot_id, owner_id, household_id, member_id,
                        prompt_version, schema_version, model_id
                    )
                    SELECT :id, :inbox, :bot, :owner, member.household_id, member.id,
                        :prompt, :schema, :model
                    FROM member
                    WHERE member.telegram_user_id = :owner
                    ON CONFLICT (inbox_id) DO NOTHING
                    """.trimIndent(),
                ).param("id", UUID.randomUUID())
                .param("inbox", message.id)
                .param("bot", botId)
                .param("owner", message.ownerId)
                .param("prompt", promptVersion)
                .param("schema", schemaVersion)
                .param("model", modelId)
                .update()
        if (inserted == 0) return false
        val deferred =
            jdbc
                .sql(
                    "UPDATE telegram_inbox SET status = 'OPENAI_PENDING' " +
                        "WHERE id = ? AND bot_id = ? AND owner_id = ? AND status = 'RECEIVED'",
                ).params(message.id, botId, message.ownerId)
                .update()
        check(deferred == 1) { "OpenAI inbox transition conflict" }
        return true
    }

    @Transactional
    override fun claim(
        now: Instant,
        usageDate: LocalDate,
        leaseDuration: Duration,
        requestUnits: Int,
        dailyRequestLimit: Int,
    ): RecognitionJob? {
        require(leaseDuration > Duration.ZERO)
        require(requestUnits > 0 && dailyRequestLimit >= requestUnits)
        val candidate =
            jdbc
                .sql(
                    """
                    SELECT id FROM openai_recognition_job
                    WHERE status = 'PENDING' OR (status = 'PROCESSING' AND lease_until <= :now)
                    ORDER BY inbox_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                    """.trimIndent(),
                ).param("now", now.atOffset(ZoneOffset.UTC))
                .query(UUID::class.java)
                .optional()
                .orElse(null) ?: return null
        jdbc
            .sql("INSERT INTO openai_daily_usage(usage_date) VALUES (?) ON CONFLICT DO NOTHING")
            .param(usageDate)
            .update()
        val used =
            jdbc
                .sql("SELECT request_units FROM openai_daily_usage WHERE usage_date = ? FOR UPDATE")
                .param(usageDate)
                .query(Int::class.java)
                .single()
        val networkAllowed = used + requestUnits <= dailyRequestLimit
        if (networkAllowed) {
            jdbc
                .sql(
                    "UPDATE openai_daily_usage SET request_units = request_units + ?, updated_at = ? " +
                        "WHERE usage_date = ?",
                ).params(requestUnits, now.atOffset(ZoneOffset.UTC), usageDate)
                .update()
        }
        val token = UUID.randomUUID()
        val updated =
            jdbc
                .sql(
                    """
                    UPDATE openai_recognition_job
                    SET status = 'PROCESSING', attempt_count = attempt_count + 1,
                        lease_token = :token, lease_until = :leaseUntil,
                        usage_date = CASE WHEN :allowed THEN :usageDate ELSE NULL END,
                        last_error_code = NULL, updated_at = :now
                    WHERE id = :id
                    """.trimIndent(),
                ).param("token", token)
                .param("leaseUntil", now.plus(leaseDuration).atOffset(ZoneOffset.UTC))
                .param("allowed", networkAllowed)
                .param("usageDate", usageDate)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("id", candidate)
                .update()
        check(updated == 1) { "OpenAI job claim conflict" }
        return load(candidate, token, networkAllowed)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun complete(
        job: RecognitionJob,
        completion: RecognitionCompletion,
        now: Instant,
    ) {
        val usage = completion.usage
        if (usage != null) {
            val usageDate = checkNotNull(job.usageDate) { "Usage date is required for an OpenAI response" }
            val updated =
                jdbc
                    .sql(
                        """
                        UPDATE openai_daily_usage
                        SET input_tokens = input_tokens + :input,
                            output_tokens = output_tokens + :output,
                            total_tokens = total_tokens + :total,
                            updated_at = :now
                        WHERE usage_date = :usageDate
                        """.trimIndent(),
                    ).param("input", usage.inputTokens)
                    .param("output", usage.outputTokens)
                    .param("total", usage.totalTokens)
                    .param("now", now.atOffset(ZoneOffset.UTC))
                    .param("usageDate", usageDate)
                    .update()
            check(updated == 1) { "OpenAI usage reservation is missing" }
        }
        val changed =
            jdbc
                .sql(
                    """
                    UPDATE openai_recognition_job
                    SET status = :status, lease_token = NULL, lease_until = NULL,
                        request_id = :requestId, response_id = :responseId,
                        input_tokens = :inputTokens, output_tokens = :outputTokens,
                        total_tokens = :totalTokens,
                        latency_ms = :latency, last_error_code = :error,
                        completed_at = :now, updated_at = :now
                    WHERE id = :id AND status = 'PROCESSING' AND lease_token = :token
                    """.trimIndent(),
                ).param("status", completion.status.name)
                .param("requestId", completion.requestId)
                .param("responseId", completion.responseId)
                .param("inputTokens", usage?.inputTokens)
                .param("outputTokens", usage?.outputTokens)
                .param("totalTokens", usage?.totalTokens)
                .param("latency", completion.latencyMillis)
                .param("error", completion.errorCode)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("id", job.id)
                .param("token", job.leaseToken)
                .update()
        check(changed == 1) { "OpenAI job lease lost" }
    }

    @Suppress("LongMethod") // Hydrates one job and its allowed category snapshot from two bounded queries.
    private fun load(
        id: UUID,
        token: UUID,
        networkAllowed: Boolean,
    ): RecognitionJob {
        val row =
            jdbc
                .sql(
                    """
                    SELECT job.*, inbox.text, inbox.message_sent_at, inbox.event_kind,
                        inbox.callback_query_id, inbox.telegram_message_id
                    FROM openai_recognition_job job
                    JOIN telegram_inbox inbox ON inbox.id = job.inbox_id
                    WHERE job.id = ? AND job.lease_token = ?
                    """.trimIndent(),
                ).params(id, token)
                .query { result, _ ->
                    RecognitionJobRow(
                        result.getObject("id", UUID::class.java),
                        InboxMessage(
                            result.getLong("inbox_id"),
                            result.getLong("owner_id"),
                            result.getString("text"),
                            result.getObject("message_sent_at", OffsetDateTime::class.java)?.toInstant(),
                            IncomingKind.valueOf(result.getString("event_kind")),
                            result.getString("callback_query_id"),
                            result.getObject("telegram_message_id", Long::class.javaObjectType),
                        ),
                        result.getLong("bot_id"),
                        result.getObject("household_id", UUID::class.java),
                        result.getString("model_id"),
                        result.getString("prompt_version"),
                        result.getString("schema_version"),
                        result.getInt("attempt_count"),
                        result.getObject("usage_date", LocalDate::class.java),
                    )
                }.single()
        val categories =
            jdbc
                .sql("SELECT id, name, kind FROM category WHERE household_id = ? AND NOT archived ORDER BY name, id")
                .param(row.householdId)
                .query { result, _ ->
                    RecognitionCategory(
                        result.getObject("id", UUID::class.java),
                        result.getString("name"),
                        if (CategoryKind.valueOf(result.getString("kind")) == CategoryKind.INCOME) {
                            Direction.INCOME
                        } else {
                            Direction.EXPENSE
                        },
                    )
                }.list()
        return RecognitionJob(
            row.id,
            row.message,
            row.botId,
            categories,
            row.modelId,
            row.promptVersion,
            row.schemaVersion,
            row.attemptCount,
            row.usageDate,
            token,
            networkAllowed,
        )
    }

    private data class RecognitionJobRow(
        val id: UUID,
        val message: InboxMessage,
        val botId: Long,
        val householdId: UUID,
        val modelId: String,
        val promptVersion: String,
        val schemaVersion: String,
        val attemptCount: Int,
        val usageDate: LocalDate?,
    )
}
