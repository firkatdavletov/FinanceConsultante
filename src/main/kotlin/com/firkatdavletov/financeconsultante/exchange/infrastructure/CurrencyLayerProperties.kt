package com.firkatdavletov.financeconsultante.exchange.infrastructure

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId

private const val DEFAULT_FETCH_HOUR = 7
private const val REPORT_HOUR = 8
private const val DEFAULT_POLL_MINUTES = 1L
private const val DEFAULT_INITIAL_SECONDS = 5L
private const val DEFAULT_CONNECT_SECONDS = 3L
private const val DEFAULT_READ_SECONDS = 5L
private const val DEFAULT_MAX_QUOTE_AGE_HOURS = 30L
private const val DEFAULT_LEASE_MINUTES = 2L
private const val DEFAULT_RETRY_MINUTES = 5L
private const val DEFAULT_MAX_RETRY_HOURS = 6L

@ConfigurationProperties("app.exchange-rates")
data class CurrencyLayerProperties(
    val enabled: Boolean = false,
    val accessKey: String = "",
    val baseUrl: String = "https://api.currencylayer.com",
    val zoneId: ZoneId = ZoneId.of("Asia/Almaty"),
    val fetchTime: LocalTime = LocalTime.of(DEFAULT_FETCH_HOUR, 0),
    val pollDelay: Duration = Duration.ofMinutes(DEFAULT_POLL_MINUTES),
    val initialDelay: Duration = Duration.ofSeconds(DEFAULT_INITIAL_SECONDS),
    val connectTimeout: Duration = Duration.ofSeconds(DEFAULT_CONNECT_SECONDS),
    val readTimeout: Duration = Duration.ofSeconds(DEFAULT_READ_SECONDS),
    val maxQuoteAge: Duration = Duration.ofHours(DEFAULT_MAX_QUOTE_AGE_HOURS),
    val leaseDuration: Duration = Duration.ofMinutes(DEFAULT_LEASE_MINUTES),
    val retryBaseDelay: Duration = Duration.ofMinutes(DEFAULT_RETRY_MINUTES),
    val retryMaxDelay: Duration = Duration.ofHours(DEFAULT_MAX_RETRY_HOURS),
) {
    fun validateEnabled() {
        require(accessKey.isNotBlank()) { "CURRENCYLAYER_ACCESS_KEY is required when exchange rates are enabled" }
        require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://127.0.0.1:")) {
            "Currencylayer base URL must use HTTPS"
        }
        require(zoneId == ZoneId.of("Asia/Almaty")) { "Exchange-rate zone must be Asia/Almaty" }
        require(fetchTime < LocalTime.of(REPORT_HOUR, 0)) { "Exchange rate must be scheduled before the 08:00 report" }
        require(
            listOf(pollDelay, initialDelay, connectTimeout, readTimeout, maxQuoteAge, leaseDuration).all {
                !it.isNegative
            },
        ) {
            "Exchange-rate durations cannot be negative"
        }
        require(
            pollDelay > Duration.ZERO &&
                connectTimeout > Duration.ZERO &&
                readTimeout > Duration.ZERO &&
                maxQuoteAge > Duration.ZERO &&
                leaseDuration > Duration.ZERO,
        ) {
            "Exchange-rate poll delay, timeouts, quote age, and lease must be positive"
        }
        require(retryBaseDelay > Duration.ZERO && retryMaxDelay >= retryBaseDelay) { "Invalid retry delays" }
    }
}
