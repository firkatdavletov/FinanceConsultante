package com.firkatdavletov.financeconsultante.ledger.domain

import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate

data class DailyExchangeRate(
    val date: LocalDate,
    val rubKzt: BigDecimal,
    val source: String,
    val fetchedAt: Instant,
    val quotedAt: Instant = fetchedAt,
) {
    init {
        require(rubKzt.signum() > 0 && rubKzt <= MAX_RATE && rubKzt.scale() <= RATE_SCALE) { "Invalid RUB/KZT rate" }
        require(SOURCE_PATTERN.matches(source)) { "Invalid rate source code" }
        require(quotedAt <= fetchedAt.plus(FUTURE_QUOTE_TOLERANCE)) { "Quote timestamp is in the future" }
    }

    fun convert(rubles: Money): Money {
        require(rubles.currency == Currency.RUB) { "Conversion requires RUB" }
        // Round the converted amount once; never round the user's original amount.
        return Money.of((rubles.amount * rubKzt).setScale(2, RoundingMode.HALF_UP))
    }

    companion object {
        private const val RATE_SCALE = 8
        private val MAX_RATE = BigDecimal("99999999999.99999999")
        private val SOURCE_PATTERN = Regex("[A-Za-z0-9:_-]{1,64}")
        private val FUTURE_QUOTE_TOLERANCE = java.time.Duration.ofMinutes(5)
    }
}
