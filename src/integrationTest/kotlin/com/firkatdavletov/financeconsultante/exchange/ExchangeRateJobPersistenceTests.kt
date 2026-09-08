package com.firkatdavletov.financeconsultante.exchange

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.exchange.application.ExchangeRateJobRepository
import com.firkatdavletov.financeconsultante.exchange.application.ExchangeRateProvider
import com.firkatdavletov.financeconsultante.exchange.application.ExchangeRateRunResult
import com.firkatdavletov.financeconsultante.exchange.application.ExchangeRateWorker
import com.firkatdavletov.financeconsultante.exchange.domain.ExchangeRateJob
import com.firkatdavletov.financeconsultante.exchange.domain.ExchangeRateQuote
import com.firkatdavletov.financeconsultante.ledger.application.ExchangeRateRepository
import com.firkatdavletov.financeconsultante.ledger.domain.DailyExchangeRate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Suppress("MagicNumber")
class ExchangeRateJobPersistenceTests
    @Autowired
    constructor(
        private val jobs: ExchangeRateJobRepository,
        private val rates: ExchangeRateRepository,
        private val jdbc: JdbcClient,
        private val transactions: PlatformTransactionManager,
    ) : PostgresIntegrationTest() {
        @BeforeEach
        fun removeSyntheticExchangeRateRows() {
            jdbc.sql("DELETE FROM exchange_rate_job WHERE rate_date >= DATE '2040-01-01'").update()
            jdbc.sql("DELETE FROM daily_exchange_rate WHERE rate_date >= DATE '2040-01-01'").update()
        }

        @Test
        fun `catch-up creates every missed calendar date including weekend at 0700 Almaty`() {
            val tracked = LocalDate.parse("2040-01-06")
            seedCompleted(tracked)

            jobs.enqueueThrough(
                LocalDate.parse("2040-01-09"),
                LocalTime.of(7, 0),
                ZoneId.of("Asia/Almaty"),
                Instant.parse("2040-01-09T03:00:00Z"),
            )

            val rows =
                jdbc
                    .sql(
                        """
                        SELECT rate_date, due_at FROM exchange_rate_job
                        WHERE rate_date BETWEEN '2040-01-07' AND '2040-01-09'
                        ORDER BY rate_date
                        """.trimIndent(),
                    ).query { row, _ ->
                        row.getObject("rate_date", LocalDate::class.java) to
                            row.getObject("due_at", OffsetDateTime::class.java).toInstant()
                    }.list()
            assertThat(rows.map { it.first }).containsExactly(
                LocalDate.parse("2040-01-07"),
                LocalDate.parse("2040-01-08"),
                LocalDate.parse("2040-01-09"),
            )
            assertThat(rows.map { it.second }).containsOnly(
                Instant.parse("2040-01-07T02:00:00Z"),
                Instant.parse("2040-01-08T02:00:00Z"),
                Instant.parse("2040-01-09T02:00:00Z"),
            )
        }

        @Test
        fun `an explicitly requested historical date becomes a durable due job`() {
            val now = Instant.parse("2040-01-09T03:00:00Z")
            val requested = LocalDate.parse("2040-01-05")

            jobs.enqueueDate(requested, LocalTime.of(7, 0), ZoneId.of("Asia/Almaty"), now)

            assertThat(checkNotNull(jobs.claimDue(now, Duration.ofMinutes(2))).rateDate).isEqualTo(requested)
        }

        @Test
        fun `provider runs outside database transaction before result is committed`() {
            val now = Instant.parse("2044-05-06T03:00:00Z")
            val date = LocalDate.parse("2044-05-06")
            seedPending(date, now.minusSeconds(1))
            val provider =
                ExchangeRateProvider {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
                    ExchangeRateQuote(BigDecimal("5.25"), now.minusSeconds(30))
                }
            val worker =
                ExchangeRateWorker(
                    jobs,
                    provider,
                    java.time.Clock.fixed(now, ZoneOffset.UTC),
                    ZoneId.of("Asia/Almaty"),
                    LocalTime.of(7, 0),
                    Duration.ofMinutes(2),
                    Duration.ofMinutes(5),
                    Duration.ofHours(6),
                )

            assertThat(worker.runOnce()).isEqualTo(ExchangeRateRunResult.COMPLETED)
            assertThat(rates.find(date)?.rubKzt).isEqualByComparingTo("5.25")
        }

        @Test
        fun `completion atomically fixes one immutable daily rate with provider timestamps`() {
            val now = Instant.parse("2041-02-03T03:00:00Z")
            val date = LocalDate.parse("2041-02-03")
            seedPending(date, now.minusSeconds(60))
            val job = checkNotNull(jobs.claimDue(now, Duration.ofMinutes(2)))
            val proposed =
                DailyExchangeRate(
                    date,
                    BigDecimal("5.12345678"),
                    "CURRENCYLAYER_LIVE_USD_CROSS",
                    now,
                    now.minusSeconds(30),
                )

            assertThat(jobs.complete(job, proposed, now)).isEqualTo(proposed)
            assertThat(rates.find(date)).isEqualTo(proposed)
            val state = jobState(date)
            assertThat(state.status).isEqualTo("COMPLETED")
            assertThat(state.completedAt).isEqualTo(now)
            assertThat(state.leaseToken).isNull()

            val competing = proposed.copy(rubKzt = BigDecimal("9"))
            assertThat(jobs.complete(job, competing, now.plusSeconds(1))).isEqualTo(proposed)
            assertThat(rates.find(date)).isEqualTo(proposed)
        }

        @Test
        fun `retry and expired lease recover without stale worker overwriting state`() {
            val firstAttempt = Instant.parse("2042-03-04T02:00:00Z")
            val date = LocalDate.parse("2042-03-04")
            seedPending(date, firstAttempt)
            val firstJob = checkNotNull(jobs.claimDue(firstAttempt, Duration.ofMinutes(2)))
            val retryAt = firstAttempt.plusSeconds(300)

            jobs.retry(firstJob, retryAt, "http_503", firstAttempt)
            assertThat(jobs.claimDue(retryAt.minusMillis(1), Duration.ofMinutes(2))).isNull()
            val secondJob = checkNotNull(jobs.claimDue(retryAt, Duration.ofMinutes(2)))
            assertThat(secondJob.attemptCount).isEqualTo(2)
            assertThat(jobs.claimDue(retryAt.plusSeconds(119), Duration.ofMinutes(2))).isNull()
            val recovered = checkNotNull(jobs.claimDue(retryAt.plusSeconds(120), Duration.ofMinutes(2)))
            assertThat(recovered.attemptCount).isEqualTo(3)
            assertThat(recovered.leaseToken).isNotEqualTo(secondJob.leaseToken)

            jobs.retry(secondJob, retryAt.plusSeconds(600), "stale_worker", retryAt.plusSeconds(121))
            assertThat(jobState(date).leaseToken).isEqualTo(recovered.leaseToken)
        }

        @Test
        fun `two independent transactions claim a due job only once`() {
            val now = Instant.parse("2043-04-05T02:00:00Z")
            val date = LocalDate.parse("2043-04-05")
            seedPending(date, now)
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val futures =
                    (1..2).map {
                        executor.submit<ExchangeRateJob?> {
                            ready.countDown()
                            check(start.await(10, TimeUnit.SECONDS))
                            TransactionTemplate(transactions).execute {
                                jobs.claimDue(now, Duration.ofMinutes(2))
                            }
                        }
                    }
                check(ready.await(10, TimeUnit.SECONDS))
                start.countDown()
                assertThat(futures.map { it.get(20, TimeUnit.SECONDS) }.filterNotNull()).hasSize(1)
            } finally {
                executor.shutdownNow()
            }
        }

        private fun seedPending(
            date: LocalDate,
            dueAt: Instant,
        ) {
            jdbc
                .sql(
                    """
                    INSERT INTO exchange_rate_job (
                        id, rate_date, due_at, next_attempt_at, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).params(
                    UUID.randomUUID(),
                    date,
                    dueAt.atOffset(ZoneOffset.UTC),
                    dueAt.atOffset(ZoneOffset.UTC),
                    dueAt.atOffset(ZoneOffset.UTC),
                    dueAt.atOffset(ZoneOffset.UTC),
                ).update()
        }

        private fun seedCompleted(date: LocalDate) {
            val now = date.atStartOfDay(ZoneId.of("Asia/Almaty")).toInstant()
            jdbc
                .sql(
                    """
                    INSERT INTO exchange_rate_job (
                        id, rate_date, due_at, status, next_attempt_at,
                        created_at, updated_at, completed_at
                    ) VALUES (?, ?, ?, 'COMPLETED', ?, ?, ?, ?)
                    ON CONFLICT (rate_date) DO NOTHING
                    """.trimIndent(),
                ).params(
                    UUID.randomUUID(),
                    date,
                    now.atOffset(ZoneOffset.UTC),
                    now.atOffset(ZoneOffset.UTC),
                    now.atOffset(ZoneOffset.UTC),
                    now.atOffset(ZoneOffset.UTC),
                    now.atOffset(ZoneOffset.UTC),
                ).update()
        }

        private fun jobState(date: LocalDate): JobState =
            jdbc
                .sql(
                    "SELECT status, lease_token, completed_at FROM exchange_rate_job WHERE rate_date = ?",
                ).param(date)
                .query { row, _ ->
                    JobState(
                        row.getString("status"),
                        row.getObject("lease_token", UUID::class.java),
                        row.getObject("completed_at", OffsetDateTime::class.java)?.toInstant(),
                    )
                }.single()

        private data class JobState(
            val status: String,
            val leaseToken: UUID?,
            val completedAt: Instant?,
        )
    }
