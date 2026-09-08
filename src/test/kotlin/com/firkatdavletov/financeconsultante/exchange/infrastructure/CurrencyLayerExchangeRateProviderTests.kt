package com.firkatdavletov.financeconsultante.exchange.infrastructure

import com.firkatdavletov.financeconsultante.exchange.domain.ExchangeRateProviderException
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class CurrencyLayerExchangeRateProviderTests {
    private lateinit var server: WireMockServer
    private val fetchedAt = Instant.parse("2026-09-08T15:00:00Z")

    @BeforeEach
    fun startServer() {
        server = WireMockServer(options().dynamicPort())
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.stop()
    }

    @Test
    fun `USD quotes from supplied response produce KZT per one RUB`() {
        stubJson(
            """
            {
              "success": true,
              "timestamp": 1788878465,
              "source": "USD",
              "quotes": {"USDKZT": 454.017421, "USDRUB": 85.923486}
            }
            """.trimIndent(),
        )

        val quote = provider().fetchRubKzt()

        assertThat(quote.rubKzt).isEqualByComparingTo(BigDecimal("5.28397348"))
        assertThat(quote.quotedAt).isEqualTo(Instant.parse("2026-09-08T14:41:05Z"))
        server.verify(
            getRequestedFor(urlPathEqualTo("/live"))
                .withQueryParam("access_key", equalTo("synthetic-access-key"))
                .withQueryParam("currencies", equalTo("RUB,KZT")),
        )
    }

    @Test
    fun `provider error is reduced to safe code without leaking key or response`() {
        stubJson("""{"success":false,"error":{"code":101,"info":"sensitive provider detail"}}""")

        assertThatThrownBy { provider().fetchRubKzt() }
            .isInstanceOfSatisfying(ExchangeRateProviderException::class.java) { failure ->
                assertThat(failure.code).isEqualTo("provider_101")
                assertThat(failure.message).doesNotContain("synthetic-access-key", "sensitive provider detail")
            }
    }

    @Test
    fun `invalid source missing quote and stale timestamp are rejected`() {
        listOf(
            """{"success":true,"timestamp":1788878465,"source":"EUR","quotes":{"USDKZT":454,"USDRUB":86}}""" to
                "invalid_source",
            """{"success":true,"timestamp":1788878465,"source":"USD","quotes":{"USDKZT":454}}""" to
                "missing_usdrub",
            """{"success":true,"timestamp":1788878465,"source":"USD","quotes":{"USDKZT":454,"USDRUB":0}}""" to
                "invalid_quote",
            """
            {"success":true,"timestamp":1788878465,"source":"USD",
             "quotes":{"USDKZT":100000000000,"USDRUB":0.1}}
            """.trimIndent() to "invalid_cross_rate",
            """{"success":true,"timestamp":1788696000,"source":"USD","quotes":{"USDKZT":454,"USDRUB":86}}""" to
                "stale_quote",
        ).forEach { (body, expectedCode) ->
            server.resetAll()
            stubJson(body)
            assertThatThrownBy { provider().fetchRubKzt() }
                .isInstanceOfSatisfying(ExchangeRateProviderException::class.java) { failure ->
                    assertThat(failure.code).isEqualTo(expectedCode)
                }
        }
    }

    @Test
    fun `HTTP error and timeout are retryable provider failures`() {
        server.stubFor(get(urlPathEqualTo("/live")).willReturn(aResponse().withStatus(503)))
        assertThatThrownBy { provider().fetchRubKzt() }
            .isInstanceOfSatisfying(ExchangeRateProviderException::class.java) { failure ->
                assertThat(failure.code).isEqualTo("http_503")
            }

        server.resetAll()
        server.stubFor(
            get(urlPathEqualTo("/live"))
                .willReturn(
                    aResponse().withFixedDelay(300).withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE),
                ),
        )
        assertThatThrownBy { provider(readTimeout = Duration.ofMillis(50)).fetchRubKzt() }
            .isInstanceOfSatisfying(ExchangeRateProviderException::class.java) { failure ->
                assertThat(failure.code).isEqualTo("transport")
            }
    }

    @Test
    fun `malformed payload is rejected without exposing its contents`() {
        stubJson("sensitive malformed payload")

        assertThatThrownBy { provider().fetchRubKzt() }
            .isInstanceOfSatisfying(ExchangeRateProviderException::class.java) { failure ->
                assertThat(failure.code).isEqualTo("invalid_payload")
                assertThat(failure.stackTraceToString()).doesNotContain("sensitive malformed payload")
            }
    }

    private fun stubJson(body: String) {
        server.stubFor(
            get(urlPathEqualTo("/live"))
                .willReturn(aResponse().withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE).withBody(body)),
        )
    }

    private fun provider(readTimeout: Duration = Duration.ofSeconds(1)): CurrencyLayerExchangeRateProvider {
        val loopbackBaseUrl = "http://127.0.0.1:${server.port()}"
        val properties =
            CurrencyLayerProperties(
                enabled = true,
                accessKey = "synthetic-access-key",
                baseUrl = loopbackBaseUrl,
                readTimeout = readTimeout,
            )
        properties.validateEnabled()
        val requestFactory = SimpleClientHttpRequestFactory()
        requestFactory.setConnectTimeout(Duration.ofSeconds(1))
        requestFactory.setReadTimeout(readTimeout)
        return CurrencyLayerExchangeRateProvider(
            RestClient
                .builder()
                .baseUrl(loopbackBaseUrl)
                .requestFactory(requestFactory)
                .build(),
            properties,
            Clock.fixed(fetchedAt, ZoneOffset.UTC),
        )
    }
}
