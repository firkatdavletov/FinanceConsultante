package com.firkatdavletov.financeconsultante.exchange.infrastructure

import com.firkatdavletov.financeconsultante.exchange.application.ExchangeRateProvider
import com.firkatdavletov.financeconsultante.exchange.domain.ExchangeRateProviderException
import com.firkatdavletov.financeconsultante.exchange.domain.ExchangeRateQuote
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant

class CurrencyLayerExchangeRateProvider(
    private val restClient: RestClient,
    private val properties: CurrencyLayerProperties,
    private val clock: Clock,
) : ExchangeRateProvider {
    override fun fetchRubKzt(): ExchangeRateQuote {
        val response = request()
        response.validateEnvelope()
        val (usdKzt, usdRub) = response.usdQuotes()
        val fetchedAt = clock.instant()
        val quotedAt = response.timestamp.toInstantOrFailure()
        validateQuoteTime(quotedAt, fetchedAt)
        val crossRate = usdKzt.divide(usdRub, RATE_SCALE, RoundingMode.HALF_UP)
        if (crossRate.signum() <= 0 || crossRate > MAX_CROSS_RATE) {
            throw ExchangeRateProviderException("invalid_cross_rate")
        }
        return ExchangeRateQuote(crossRate, quotedAt)
    }

    private fun CurrencyLayerResponse.validateEnvelope() {
        if (!success) {
            val providerCode =
                error
                    ?.code
                    ?.takeIf { it >= 0 }
                    ?.toString() ?: "unknown"
            throw ExchangeRateProviderException("provider_$providerCode")
        }
        if (source != SOURCE_CURRENCY) throw ExchangeRateProviderException("invalid_source")
    }

    private fun CurrencyLayerResponse.usdQuotes(): Pair<BigDecimal, BigDecimal> {
        val usdKzt = requiredQuote(USD_KZT, "missing_usdkzt")
        val usdRub = requiredQuote(USD_RUB, "missing_usdrub")
        if (usdKzt.signum() <= 0 || usdRub.signum() <= 0) throw ExchangeRateProviderException("invalid_quote")
        return usdKzt to usdRub
    }

    private fun CurrencyLayerResponse.requiredQuote(
        code: String,
        errorCode: String,
    ): BigDecimal = quotes?.get(code) ?: throw ExchangeRateProviderException(errorCode)

    private fun validateQuoteTime(
        quotedAt: Instant,
        fetchedAt: Instant,
    ) {
        if (quotedAt > fetchedAt.plus(FUTURE_TOLERANCE)) throw ExchangeRateProviderException("future_quote")
        if (quotedAt < fetchedAt.minus(properties.maxQuoteAge)) throw ExchangeRateProviderException("stale_quote")
    }

    @Suppress("SwallowedException")
    private fun request(): CurrencyLayerResponse =
        try {
            restClient
                .get()
                .uri { builder ->
                    builder
                        .path("/live")
                        .queryParam("access_key", properties.accessKey)
                        .queryParam("currencies", "RUB,KZT")
                        .build()
                }.retrieve()
                .body(CurrencyLayerResponse::class.java)
                ?: throw ExchangeRateProviderException("empty_response")
        } catch (failure: ExchangeRateProviderException) {
            throw failure
        } catch (failure: RestClientResponseException) {
            throw ExchangeRateProviderException("http_${failure.statusCode.value()}")
        } catch (failure: ResourceAccessException) {
            throw ExchangeRateProviderException("transport", failure.safeCause())
        } catch (failure: RestClientException) {
            throw ExchangeRateProviderException("invalid_payload", failure.safeCause())
        }

    private fun Long?.toInstantOrFailure(): Instant {
        val timestamp = this ?: throw ExchangeRateProviderException("missing_timestamp")
        return try {
            Instant.ofEpochSecond(timestamp)
        } catch (failure: DateTimeException) {
            throw ExchangeRateProviderException("invalid_timestamp", failure)
        }
    }

    private fun RuntimeException.safeCause(): RuntimeException = RuntimeException(javaClass.simpleName)

    private data class CurrencyLayerResponse(
        val success: Boolean = false,
        val timestamp: Long? = null,
        val source: String? = null,
        val quotes: Map<String, BigDecimal>? = null,
        val error: CurrencyLayerError? = null,
    )

    private data class CurrencyLayerError(
        val code: Int? = null,
    )

    companion object {
        private const val SOURCE_CURRENCY = "USD"
        private const val USD_KZT = "USDKZT"
        private const val USD_RUB = "USDRUB"
        private const val RATE_SCALE = 8
        private val MAX_CROSS_RATE = BigDecimal("99999999999.99999999")
        private val FUTURE_TOLERANCE: Duration = Duration.ofMinutes(5)
    }
}
