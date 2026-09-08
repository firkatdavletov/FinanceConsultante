package com.firkatdavletov.financeconsultante.ledger.infrastructure

import com.firkatdavletov.financeconsultante.ledger.application.ExchangeRateRepository
import com.firkatdavletov.financeconsultante.ledger.domain.DailyExchangeRate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class JdbcExchangeRateRepository(
    private val jdbc: JdbcClient,
) : ExchangeRateRepository {
    override fun find(date: LocalDate): DailyExchangeRate? =
        jdbc
            .sql(
                "SELECT * FROM daily_exchange_rate WHERE rate_date = ?",
            ).param(date)
            .query { row, _ ->
                DailyExchangeRate(
                    row.getObject("rate_date", LocalDate::class.java),
                    row.getBigDecimal("rub_kzt"),
                    row.getString("source"),
                    row.getObject("fetched_at", OffsetDateTime::class.java).toInstant(),
                    row.getObject("quoted_at", OffsetDateTime::class.java).toInstant(),
                )
            }.optional()
            .orElse(null)

    @Transactional
    override fun fix(rate: DailyExchangeRate): DailyExchangeRate {
        jdbc
            .sql(
                """
                INSERT INTO daily_exchange_rate (rate_date, rub_kzt, source, quoted_at, fetched_at)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (rate_date) DO NOTHING
                """.trimIndent(),
            ).params(
                rate.date,
                rate.rubKzt,
                rate.source,
                rate.quotedAt.atOffset(ZoneOffset.UTC),
                rate.fetchedAt.atOffset(ZoneOffset.UTC),
            ).update()
        val saved = checkNotNull(find(rate.date))
        require(saved.rubKzt.compareTo(rate.rubKzt) == 0 && saved.source == rate.source) {
            "Daily exchange rate is already fixed with a different snapshot"
        }
        return saved
    }
}
