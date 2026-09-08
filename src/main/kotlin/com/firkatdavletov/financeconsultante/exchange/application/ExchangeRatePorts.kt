package com.firkatdavletov.financeconsultante.exchange.application

import com.firkatdavletov.financeconsultante.exchange.domain.ExchangeRateJob
import com.firkatdavletov.financeconsultante.exchange.domain.ExchangeRateQuote
import com.firkatdavletov.financeconsultante.ledger.domain.DailyExchangeRate
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

fun interface ExchangeRateProvider {
    fun fetchRubKzt(): ExchangeRateQuote
}

interface ExchangeRateJobRepository {
    fun enqueueDate(
        rateDate: LocalDate,
        fetchTime: LocalTime,
        zoneId: ZoneId,
        now: Instant,
    )

    fun enqueueThrough(
        today: LocalDate,
        fetchTime: LocalTime,
        zoneId: ZoneId,
        now: Instant,
    )

    fun claimDue(
        now: Instant,
        leaseDuration: Duration,
    ): ExchangeRateJob?

    fun complete(
        job: ExchangeRateJob,
        rate: DailyExchangeRate,
        now: Instant,
    ): DailyExchangeRate

    fun retry(
        job: ExchangeRateJob,
        nextAttemptAt: Instant,
        errorCode: String,
        now: Instant,
    )
}
