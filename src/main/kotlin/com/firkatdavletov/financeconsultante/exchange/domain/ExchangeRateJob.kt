package com.firkatdavletov.financeconsultante.exchange.domain

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class ExchangeRateQuote(
    val rubKzt: BigDecimal,
    val quotedAt: Instant,
)

data class ExchangeRateJob(
    val id: UUID,
    val rateDate: LocalDate,
    val attemptCount: Int,
    val leaseToken: UUID,
)

class ExchangeRateProviderException(
    val code: String,
    cause: Throwable? = null,
) : RuntimeException("Exchange-rate provider request failed", cause) {
    init {
        require(ERROR_CODE_PATTERN.matches(code)) { "Invalid exchange-rate error code" }
    }

    companion object {
        private val ERROR_CODE_PATTERN = Regex("[a-z0-9_:-]{1,64}")
    }
}
