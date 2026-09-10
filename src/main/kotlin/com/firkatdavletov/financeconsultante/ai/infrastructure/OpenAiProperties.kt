package com.firkatdavletov.financeconsultante.ai.infrastructure

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

@ConfigurationProperties("app.openai")
data class OpenAiProperties(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val baseUrl: String = "https://api.openai.com/v1",
    val requestTimeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS),
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    val maxOutputTokens: Long = DEFAULT_MAX_OUTPUT_TOKENS,
    val dailyRequestLimit: Int = DEFAULT_DAILY_REQUEST_LIMIT,
    val leaseDuration: Duration = Duration.ofMinutes(DEFAULT_LEASE_MINUTES),
    val pollDelay: Duration = Duration.ofSeconds(DEFAULT_POLL_SECONDS),
) {
    val requestUnits: Int get() = maxRetries + 1

    fun validateEnabled() {
        require(apiKey.isNotBlank()) { "OPENAI_API_KEY is required when OpenAI is enabled" }
        require(MODEL.matches(model)) { "OPENAI_MODEL must be an exact safe model ID" }
        val uri = URI(baseUrl)
        val official = uri.scheme == "https" && uri.host == "api.openai.com" && uri.path == "/v1"
        val local = uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1")
        require(uri.userInfo == null && uri.query == null && uri.fragment == null && (official || local)) {
            "OpenAI endpoint must be official HTTPS or a local test server"
        }
        require(requestTimeout > Duration.ZERO && requestTimeout <= Duration.ofSeconds(MAX_TIMEOUT_SECONDS))
        require(maxRetries in 0..MAX_RETRIES)
        require(maxOutputTokens in MIN_OUTPUT_TOKENS..MAX_OUTPUT_TOKENS)
        require(dailyRequestLimit in requestUnits..MAX_DAILY_REQUEST_LIMIT)
        require(leaseDuration >= requestTimeout.multipliedBy(requestUnits.toLong()))
        require(leaseDuration <= Duration.ofMinutes(MAX_LEASE_MINUTES))
        require(pollDelay > Duration.ZERO && pollDelay <= Duration.ofMinutes(1))
    }

    companion object {
        const val DEFAULT_MODEL = "gpt-4o-mini-2024-07-18"
        const val PROMPT_VERSION = "transaction-recognition-v1"
        const val SCHEMA_VERSION = "transaction-recognition-v1"
        private const val DEFAULT_TIMEOUT_SECONDS = 10L
        private const val DEFAULT_MAX_RETRIES = 1
        private const val DEFAULT_MAX_OUTPUT_TOKENS = 256L
        private const val DEFAULT_DAILY_REQUEST_LIMIT = 50
        private const val DEFAULT_LEASE_MINUTES = 2L
        private const val DEFAULT_POLL_SECONDS = 1L
        private const val MAX_TIMEOUT_SECONDS = 30L
        private const val MAX_RETRIES = 2
        private const val MIN_OUTPUT_TOKENS = 64L
        private const val MAX_OUTPUT_TOKENS = 512L
        private const val MAX_DAILY_REQUEST_LIMIT = 10_000
        private const val MAX_LEASE_MINUTES = 10L
        private val MODEL = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    }
}
