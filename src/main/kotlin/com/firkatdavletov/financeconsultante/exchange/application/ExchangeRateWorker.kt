package com.firkatdavletov.financeconsultante.exchange.application

import com.firkatdavletov.financeconsultante.exchange.domain.ExchangeRateProviderException
import com.firkatdavletov.financeconsultante.ledger.domain.DailyExchangeRate
import java.time.Clock
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.min

enum class ExchangeRateRunResult {
    IDLE,
    COMPLETED,
    RETRY_SCHEDULED,
}

class ExchangeRateWorker(
    private val jobs: ExchangeRateJobRepository,
    private val provider: ExchangeRateProvider,
    private val clock: Clock,
    private val zoneId: ZoneId,
    private val fetchTime: LocalTime,
    private val leaseDuration: Duration,
    private val retryBaseDelay: Duration,
    private val retryMaxDelay: Duration,
) {
    fun runOnce(): ExchangeRateRunResult {
        val now = clock.instant()
        jobs.enqueueThrough(now.atZone(zoneId).toLocalDate(), fetchTime, zoneId, now)
        val job = jobs.claimDue(now, leaseDuration) ?: return ExchangeRateRunResult.IDLE
        return try {
            val quote = provider.fetchRubKzt()
            jobs.complete(
                job,
                DailyExchangeRate(
                    date = job.rateDate,
                    rubKzt = quote.rubKzt,
                    source = RATE_SOURCE,
                    fetchedAt = clock.instant(),
                    quotedAt = quote.quotedAt,
                ),
                clock.instant(),
            )
            ExchangeRateRunResult.COMPLETED
        } catch (failure: ExchangeRateProviderException) {
            val failedAt = clock.instant()
            jobs.retry(job, failedAt.plus(retryDelay(job.attemptCount)), failure.code, failedAt)
            ExchangeRateRunResult.RETRY_SCHEDULED
        }
    }

    private fun retryDelay(attemptCount: Int): Duration {
        val exponent = min((attemptCount - 1).coerceAtLeast(0), MAX_BACKOFF_EXPONENT)
        val multiplier = 1L shl exponent
        val multiplied = runCatching { retryBaseDelay.multipliedBy(multiplier) }.getOrDefault(retryMaxDelay)
        return if (multiplied > retryMaxDelay) retryMaxDelay else multiplied
    }

    companion object {
        const val RATE_SOURCE = "CURRENCYLAYER_LIVE_USD_CROSS"
        private const val MAX_BACKOFF_EXPONENT = 20
    }
}
