package com.firkatdavletov.financeconsultante.exchange.infrastructure

import com.firkatdavletov.financeconsultante.exchange.application.ExchangeRateJobRepository
import com.firkatdavletov.financeconsultante.exchange.domain.ExchangeRateJob
import com.firkatdavletov.financeconsultante.ledger.application.ExchangeRateRepository
import com.firkatdavletov.financeconsultante.ledger.domain.DailyExchangeRate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JdbcExchangeRateJobRepository(
    private val jdbc: JdbcClient,
    private val exchangeRates: ExchangeRateRepository,
) : ExchangeRateJobRepository {
    @Transactional
    override fun enqueueDate(
        rateDate: LocalDate,
        fetchTime: LocalTime,
        zoneId: ZoneId,
        now: Instant,
    ) {
        require(!rateDate.isAfter(now.atZone(zoneId).toLocalDate())) { "Future exchange-rate date is not allowed" }
        enqueue(rateDate, fetchTime, zoneId, now)
    }

    @Transactional
    override fun enqueueThrough(
        today: LocalDate,
        fetchTime: LocalTime,
        zoneId: ZoneId,
        now: Instant,
    ) {
        val latest =
            jdbc
                .sql(
                    """
                    SELECT max(rate_date) FROM (
                        SELECT rate_date FROM exchange_rate_job WHERE rate_date <= :today
                        UNION ALL
                        SELECT rate_date FROM daily_exchange_rate WHERE rate_date <= :today
                    ) tracked
                    """.trimIndent(),
                ).param("today", today)
                .query(LocalDate::class.java)
                .optional()
                .orElse(null)
        var date = latest?.plusDays(1) ?: today
        while (!date.isAfter(today)) {
            enqueue(date, fetchTime, zoneId, now)
            date = date.plusDays(1)
        }
        ensureTodayIsTracked(today, fetchTime, zoneId, now)
    }

    @Transactional
    override fun claimDue(
        now: Instant,
        leaseDuration: Duration,
    ): ExchangeRateJob? {
        require(leaseDuration > Duration.ZERO) { "Lease duration must be positive" }
        val leaseToken = UUID.randomUUID()
        return jdbc
            .sql(
                """
                WITH candidate AS (
                    SELECT id FROM exchange_rate_job
                    WHERE (status IN ('PENDING', 'RETRY') AND next_attempt_at <= :now)
                       OR (status = 'PROCESSING' AND lease_until <= :now)
                    ORDER BY rate_date
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE exchange_rate_job job
                SET status = 'PROCESSING', attempt_count = attempt_count + 1,
                    lease_token = :token, lease_until = :lease_until,
                    last_error_code = NULL, updated_at = :now
                FROM candidate
                WHERE job.id = candidate.id
                RETURNING job.id, job.rate_date, job.attempt_count, job.lease_token
                """.trimIndent(),
            ).param("now", now.atOffset(ZoneOffset.UTC))
            .param("token", leaseToken)
            .param("lease_until", now.plus(leaseDuration).atOffset(ZoneOffset.UTC))
            .query { row, _ ->
                ExchangeRateJob(
                    row.getObject("id", UUID::class.java),
                    row.getObject("rate_date", LocalDate::class.java),
                    row.getInt("attempt_count"),
                    row.getObject("lease_token", UUID::class.java),
                )
            }.optional()
            .orElse(null)
    }

    @Transactional
    override fun complete(
        job: ExchangeRateJob,
        rate: DailyExchangeRate,
        now: Instant,
    ): DailyExchangeRate {
        require(rate.date == job.rateDate) { "Exchange-rate date mismatch" }
        val lock = lock(job.id)
        if (lock.status == "COMPLETED") return checkNotNull(exchangeRates.find(job.rateDate))
        require(lock.status == "PROCESSING" && lock.leaseToken == job.leaseToken) { "Exchange-rate lease lost" }
        val saved = exchangeRates.find(job.rateDate) ?: exchangeRates.fix(rate)
        val updated =
            jdbc
                .sql(
                    """
                    UPDATE exchange_rate_job
                    SET status = 'COMPLETED', lease_token = NULL, lease_until = NULL,
                        last_error_code = NULL, completed_at = :now, updated_at = :now
                    WHERE id = :id AND status = 'PROCESSING' AND lease_token = :token
                    """.trimIndent(),
                ).param("now", now.atOffset(ZoneOffset.UTC))
                .param("id", job.id)
                .param("token", job.leaseToken)
                .update()
        check(updated == 1) { "Exchange-rate lease lost" }
        return saved
    }

    @Transactional
    override fun retry(
        job: ExchangeRateJob,
        nextAttemptAt: Instant,
        errorCode: String,
        now: Instant,
    ) {
        require(ERROR_CODE_PATTERN.matches(errorCode)) { "Invalid exchange-rate error code" }
        require(nextAttemptAt > now) { "Retry must be scheduled in the future" }
        jdbc
            .sql(
                """
                UPDATE exchange_rate_job
                SET status = 'RETRY', next_attempt_at = :next_attempt,
                    lease_token = NULL, lease_until = NULL,
                    last_error_code = :error_code, updated_at = :now
                WHERE id = :id AND status = 'PROCESSING' AND lease_token = :token
                """.trimIndent(),
            ).param("next_attempt", nextAttemptAt.atOffset(ZoneOffset.UTC))
            .param("error_code", errorCode)
            .param("now", now.atOffset(ZoneOffset.UTC))
            .param("id", job.id)
            .param("token", job.leaseToken)
            .update()
    }

    private fun ensureTodayIsTracked(
        today: LocalDate,
        fetchTime: LocalTime,
        zoneId: ZoneId,
        now: Instant,
    ) {
        val tracked =
            jdbc
                .sql("SELECT EXISTS (SELECT 1 FROM exchange_rate_job WHERE rate_date = ?)")
                .param(today)
                .query(Boolean::class.java)
                .single()
        if (!tracked) enqueue(today, fetchTime, zoneId, now)
    }

    private fun enqueue(
        date: LocalDate,
        fetchTime: LocalTime,
        zoneId: ZoneId,
        now: Instant,
    ) {
        val dueAt = date.atTime(fetchTime).atZone(zoneId).toInstant()
        jdbc
            .sql(
                """
                INSERT INTO exchange_rate_job (
                    id, rate_date, due_at, status, next_attempt_at,
                    created_at, updated_at, completed_at
                )
                SELECT :id, :rate_date, :due_at,
                    CASE WHEN rate.rate_date IS NULL THEN 'PENDING' ELSE 'COMPLETED' END,
                    :due_at, :now, :now,
                    CASE WHEN rate.rate_date IS NULL THEN NULL ELSE :now END
                FROM (SELECT CAST(:rate_date AS date) AS requested_date) request
                LEFT JOIN daily_exchange_rate rate ON rate.rate_date = request.requested_date
                ON CONFLICT (rate_date) DO NOTHING
                """.trimIndent(),
            ).param("id", UUID.randomUUID())
            .param("rate_date", date)
            .param("due_at", dueAt.atOffset(ZoneOffset.UTC))
            .param("now", now.atOffset(ZoneOffset.UTC))
            .update()
    }

    private fun lock(id: UUID): JobLock =
        jdbc
            .sql("SELECT status, lease_token FROM exchange_rate_job WHERE id = ? FOR UPDATE")
            .param(id)
            .query { row, _ -> JobLock(row.getString("status"), row.getObject("lease_token", UUID::class.java)) }
            .single()

    private data class JobLock(
        val status: String,
        val leaseToken: UUID?,
    )

    companion object {
        private val ERROR_CODE_PATTERN = Regex("[a-z0-9_:-]{1,64}")
    }
}
