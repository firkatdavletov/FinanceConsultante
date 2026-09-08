package com.firkatdavletov.financeconsultante.exchange.application

import com.firkatdavletov.financeconsultante.exchange.domain.ExchangeRateJob
import com.firkatdavletov.financeconsultante.exchange.domain.ExchangeRateProviderException
import com.firkatdavletov.financeconsultante.exchange.domain.ExchangeRateQuote
import com.firkatdavletov.financeconsultante.ledger.domain.DailyExchangeRate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class ExchangeRateWorkerTests {
    private val now = Instant.parse("2026-09-08T02:00:00Z")
    private val zone = ZoneId.of("Asia/Almaty")

    @Test
    fun `due job stores quote for local date outside provider call`() {
        val job = ExchangeRateJob(UUID.randomUUID(), LocalDate.parse("2026-09-08"), 1, UUID.randomUUID())
        val jobs = FakeJobs(job)
        val worker = worker(jobs) { ExchangeRateQuote(BigDecimal("5.25"), now.minusSeconds(60)) }

        assertThat(worker.runOnce()).isEqualTo(ExchangeRateRunResult.COMPLETED)
        assertThat(jobs.enqueuedDate).isEqualTo(LocalDate.parse("2026-09-08"))
        assertThat(jobs.fetchTime).isEqualTo(LocalTime.of(7, 0))
        assertThat(jobs.completed?.date).isEqualTo(job.rateDate)
        assertThat(jobs.completed?.rubKzt).isEqualByComparingTo("5.25")
        assertThat(jobs.completed?.source).isEqualTo(ExchangeRateWorker.RATE_SOURCE)
        assertThat(jobs.completed?.quotedAt).isEqualTo(now.minusSeconds(60))
    }

    @Test
    fun `provider failure schedules bounded exponential retry`() {
        val job = ExchangeRateJob(UUID.randomUUID(), LocalDate.parse("2026-09-08"), 3, UUID.randomUUID())
        val jobs = FakeJobs(job)
        val worker = worker(jobs) { throw ExchangeRateProviderException("http_503") }

        assertThat(worker.runOnce()).isEqualTo(ExchangeRateRunResult.RETRY_SCHEDULED)
        assertThat(jobs.retryAt).isEqualTo(now.plus(Duration.ofMinutes(20)))
        assertThat(jobs.errorCode).isEqualTo("http_503")
        assertThat(jobs.completed).isNull()
    }

    @Test
    fun `tick without due job does not call provider`() {
        val jobs = FakeJobs(null)
        var called = false
        val worker =
            worker(jobs) {
                called = true
                ExchangeRateQuote(BigDecimal.ONE, now)
            }

        assertThat(worker.runOnce()).isEqualTo(ExchangeRateRunResult.IDLE)
        assertThat(called).isFalse()
        assertThat(jobs.enqueuedDate).isEqualTo(LocalDate.parse("2026-09-08"))
    }

    @Test
    fun `calendar date follows Almaty across the UTC day boundary`() {
        val jobs = FakeJobs(null)
        val utcPreviousDay = Instant.parse("2026-09-07T19:00:00Z")

        worker(jobs, utcPreviousDay) { ExchangeRateQuote(BigDecimal.ONE, utcPreviousDay) }.runOnce()

        assertThat(jobs.enqueuedDate).isEqualTo(LocalDate.parse("2026-09-08"))
    }

    private fun worker(
        jobs: FakeJobs,
        clockInstant: Instant = now,
        fetch: () -> ExchangeRateQuote,
    ): ExchangeRateWorker =
        ExchangeRateWorker(
            jobs,
            ExchangeRateProvider { fetch() },
            Clock.fixed(clockInstant, ZoneOffset.UTC),
            zone,
            LocalTime.of(7, 0),
            Duration.ofMinutes(2),
            Duration.ofMinutes(5),
            Duration.ofHours(6),
        )

    private class FakeJobs(
        private val dueJob: ExchangeRateJob?,
    ) : ExchangeRateJobRepository {
        var enqueuedDate: LocalDate? = null
        var fetchTime: LocalTime? = null
        var completed: DailyExchangeRate? = null
        var retryAt: Instant? = null
        var errorCode: String? = null

        override fun enqueueDate(
            rateDate: LocalDate,
            fetchTime: LocalTime,
            zoneId: ZoneId,
            now: Instant,
        ) {
            enqueuedDate = rateDate
            this.fetchTime = fetchTime
        }

        override fun enqueueThrough(
            today: LocalDate,
            fetchTime: LocalTime,
            zoneId: ZoneId,
            now: Instant,
        ) {
            enqueuedDate = today
            this.fetchTime = fetchTime
        }

        override fun claimDue(
            now: Instant,
            leaseDuration: Duration,
        ): ExchangeRateJob? = dueJob

        override fun complete(
            job: ExchangeRateJob,
            rate: DailyExchangeRate,
            now: Instant,
        ): DailyExchangeRate {
            completed = rate
            return rate
        }

        override fun retry(
            job: ExchangeRateJob,
            nextAttemptAt: Instant,
            errorCode: String,
            now: Instant,
        ) {
            retryAt = nextAttemptAt
            this.errorCode = errorCode
        }
    }
}
