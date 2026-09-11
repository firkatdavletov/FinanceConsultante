package com.firkatdavletov.financeconsultante.budget.infrastructure

import com.firkatdavletov.financeconsultante.budget.application.DailyReportJob
import com.firkatdavletov.financeconsultante.budget.application.DailyReportJobRepository
import com.firkatdavletov.financeconsultante.budget.application.DailyReportRecipient
import com.firkatdavletov.financeconsultante.budget.application.DailyReportStatus
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
@Transactional(propagation = Propagation.MANDATORY)
class JdbcDailyReportJobRepository(
    private val jdbc: JdbcClient,
) : DailyReportJobRepository {
    override fun recipients(ownerId: Long): List<DailyReportRecipient> =
        jdbc
            .sql(
                """
                SELECT h.id AS household_id, m.id AS member_id, m.telegram_user_id, h.report_time,
                    a.accounting_started_at
                FROM household h JOIN member m ON m.household_id = h.id AND m.role = 'OWNER'
                JOIN account a ON a.household_id = h.id
                WHERE m.telegram_user_id = ?
                ORDER BY h.id
                """.trimIndent(),
            ).param(ownerId)
            .query { row, _ ->
                DailyReportRecipient(
                    row.getObject("household_id", UUID::class.java),
                    row.getObject("member_id", UUID::class.java),
                    row.getLong("telegram_user_id"),
                    row.getObject("report_time", LocalTime::class.java),
                    row.getObject("accounting_started_at", OffsetDateTime::class.java).toInstant(),
                )
            }.list()

    override fun schedule(
        job: DailyReportJob,
        now: Instant,
    ): Boolean =
        jdbc
            .sql(
                """
                INSERT INTO daily_report_job(
                    id, household_id, member_id, recipient_id, bot_id, period_start, scheduled_for,
                    catch_up_until, status, attempts, next_attempt_at, created_at, updated_at
                ) VALUES (
                    :id, :household, :member, :recipient, :bot, :date, :scheduled,
                    :catchUp, 'SCHEDULED', 0, :scheduled, :now, :now
                ) ON CONFLICT (household_id, recipient_id, report_type, period_start) DO UPDATE SET
                    scheduled_for = EXCLUDED.scheduled_for,
                    catch_up_until = EXCLUDED.catch_up_until,
                    next_attempt_at = EXCLUDED.scheduled_for,
                    updated_at = EXCLUDED.updated_at
                WHERE daily_report_job.status = 'SCHEDULED' AND daily_report_job.attempts = 0
                """.trimIndent(),
            ).param("id", job.id)
            .param("household", job.owner.householdId)
            .param("member", job.owner.memberId)
            .param("recipient", job.owner.ownerId)
            .param("bot", job.botId)
            .param("date", job.date)
            .param("scheduled", offset(job.scheduledFor))
            .param("catchUp", offset(job.catchUpUntil))
            .param("now", offset(now))
            .update() == 1

    override fun markExpired(
        botId: Long,
        now: Instant,
    ): List<DailyReportJob> {
        jdbc
            .sql(
                """
                UPDATE daily_report_job SET status = 'FAILED', lease_token = NULL, lease_until = NULL,
                    updated_at = :now
                WHERE bot_id = :bot AND status = 'PROCESSING' AND attempts >= 5 AND lease_until <= :now
                """.trimIndent(),
            ).param("bot", botId)
            .param("now", offset(now))
            .update()
        return jdbc
            .sql(
                """
                WITH expired AS (
                    UPDATE daily_report_job SET status = 'SKIPPED', lease_token = NULL, lease_until = NULL,
                        updated_at = :now
                    WHERE bot_id = :bot AND status IN ('SCHEDULED', 'PROCESSING') AND catch_up_until < :now
                    RETURNING *
                )
                SELECT expired.*, a.accounting_started_at FROM expired
                JOIN account a ON a.household_id = expired.household_id
                ORDER BY expired.period_start, expired.id
                """.trimIndent(),
            ).param("bot", botId)
            .param("now", offset(now))
            .query { row, _ -> mapJob(row) }
            .list()
    }

    override fun claim(
        botId: Long,
        now: Instant,
        leaseDuration: Duration,
    ): DailyReportJob? =
        jdbc
            .sql(
                """
                WITH candidate AS (
                    SELECT id FROM daily_report_job
                    WHERE bot_id = :bot AND scheduled_for <= :now AND next_attempt_at <= :now
                        AND catch_up_until >= :now AND attempts < 5
                        AND (status = 'SCHEDULED' OR (status = 'PROCESSING' AND lease_until <= :now))
                    ORDER BY scheduled_for, id LIMIT 1 FOR UPDATE SKIP LOCKED
                ), claimed AS (
                    UPDATE daily_report_job job SET status = 'PROCESSING', attempts = attempts + 1,
                        lease_token = :token, lease_until = :leaseUntil, updated_at = :now
                    FROM candidate WHERE job.id = candidate.id RETURNING job.*
                )
                SELECT claimed.*, a.accounting_started_at FROM claimed
                JOIN account a ON a.household_id = claimed.household_id
                """.trimIndent(),
            ).param("bot", botId)
            .param("now", offset(now))
            .param("token", UUID.randomUUID())
            .param("leaseUntil", offset(now.plus(leaseDuration)))
            .query { row, _ -> mapJob(row) }
            .optional()
            .orElse(null)

    override fun lock(job: DailyReportJob): DailyReportJob? =
        jdbc
            .sql(
                """
                SELECT job.*, a.accounting_started_at FROM daily_report_job job
                JOIN account a ON a.household_id = job.household_id
                WHERE job.id = ? AND job.status = 'PROCESSING' AND job.lease_token = ? FOR UPDATE OF job
                """.trimIndent(),
            ).params(job.id, checkNotNull(job.leaseToken))
            .query { row, _ -> mapJob(row) }
            .optional()
            .orElse(null)

    override fun queue(
        job: DailyReportJob,
        text: String,
        now: Instant,
    ) {
        ensureOutboxParent(job.botId)
        check(
            jdbc
                .sql(
                    """
                    INSERT INTO telegram_outbox(report_job_id, bot_id, owner_id, text)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (report_job_id) WHERE report_job_id IS NOT NULL DO NOTHING
                    """.trimIndent(),
                ).params(job.id, job.botId, job.owner.ownerId, text)
                .update() == 1,
        ) { "Daily report outbox item already exists" }
        check(
            jdbc
                .sql(
                    """
                    UPDATE daily_report_job SET status = 'QUEUED', lease_token = NULL, lease_until = NULL,
                        updated_at = ? WHERE id = ? AND status = 'PROCESSING' AND lease_token = ?
                    """.trimIndent(),
                ).params(offset(now), job.id, checkNotNull(job.leaseToken))
                .update() == 1,
        ) { "Daily report lease is stale" }
    }

    override fun queueSkipped(
        job: DailyReportJob,
        text: String,
        now: Instant,
    ) {
        ensureOutboxParent(job.botId)
        jdbc
            .sql(
                """
                INSERT INTO telegram_outbox(report_job_id, bot_id, owner_id, text, next_attempt_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (report_job_id) WHERE report_job_id IS NOT NULL DO NOTHING
                """.trimIndent(),
            ).params(job.id, job.botId, job.owner.ownerId, text, offset(now))
            .update()
    }

    override fun retry(
        job: DailyReportJob,
        now: Instant,
        delay: Duration,
    ) {
        jdbc
            .sql(
                """
                UPDATE daily_report_job SET
                    status = CASE WHEN attempts >= 5 THEN 'FAILED' ELSE 'SCHEDULED' END,
                    next_attempt_at = CASE WHEN attempts >= 5 THEN next_attempt_at ELSE :retryAt END,
                    lease_token = NULL, lease_until = NULL, updated_at = :now
                WHERE id = :id AND status = 'PROCESSING' AND lease_token = :token
                """.trimIndent(),
            ).param("retryAt", offset(now.plus(delay)))
            .param("now", offset(now))
            .param("id", job.id)
            .param("token", checkNotNull(job.leaseToken))
            .update()
    }

    private fun ensureOutboxParent(botId: Long) {
        jdbc
            .sql("INSERT INTO telegram_polling_state(bot_id) VALUES (?) ON CONFLICT DO NOTHING")
            .param(botId)
            .update()
    }

    private fun mapJob(row: java.sql.ResultSet): DailyReportJob =
        DailyReportJob(
            row.getObject("id", UUID::class.java),
            OwnedLedger(
                row.getObject("household_id", UUID::class.java),
                row.getObject("member_id", UUID::class.java),
                row.getLong("recipient_id"),
                row.getObject("accounting_started_at", OffsetDateTime::class.java).toInstant(),
            ),
            row.getLong("bot_id"),
            row.getObject("period_start", LocalDate::class.java),
            row.getObject("scheduled_for", OffsetDateTime::class.java).toInstant(),
            row.getObject("catch_up_until", OffsetDateTime::class.java).toInstant(),
            DailyReportStatus.valueOf(row.getString("status")),
            row.getInt("attempts"),
            row.getObject("lease_token", UUID::class.java),
        )

    private fun offset(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)
}
