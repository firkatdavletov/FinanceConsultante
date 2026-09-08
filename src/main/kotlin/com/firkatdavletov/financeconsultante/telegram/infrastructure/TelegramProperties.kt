package com.firkatdavletov.financeconsultante.telegram.infrastructure

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

@ConfigurationProperties("app.telegram")
class TelegramProperties(
    val botToken: String = "",
    val ownerId: Long = 0,
    val baseUrl: String = "https://api.telegram.org",
    val pollTimeoutSeconds: Int = 25,
    val requestTimeout: Duration = Duration.ofSeconds(DEFAULT_REQUEST_SECONDS),
    val leaseDuration: Duration = Duration.ofSeconds(DEFAULT_LEASE_SECONDS),
) {
    val botId: Long get() = botToken.substringBefore(':').toLong()

    fun validateEnabled() {
        require(Regex("[1-9][0-9]*:[A-Za-z0-9_-]+").matches(botToken)) { "Telegram bot token is required" }
        require(botToken.substringBefore(':').toLongOrNull() != null) { "Invalid Telegram bot ID" }
        require(ownerId > 0) { "Telegram owner ID is required" }
        val uri = URI(baseUrl)
        require(uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.orEmpty().isEmpty())
        require(
            baseUrl == "https://api.telegram.org" ||
                (uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1")),
        ) {
            "Telegram endpoint must be official HTTPS or a local test server"
        }
        require(pollTimeoutSeconds in 1..MAX_POLL_SECONDS)
        require(requestTimeout > Duration.ofSeconds(pollTimeoutSeconds.toLong()))
        require(requestTimeout <= Duration.ofMinutes(1))
        require(
            leaseDuration >= requestTimeout.multipliedBy(2) && leaseDuration <= Duration.ofMinutes(MAX_LEASE_MINUTES),
        )
    }

    companion object {
        private const val DEFAULT_REQUEST_SECONDS = 35L
        private const val DEFAULT_LEASE_SECONDS = 90L
        private const val MAX_POLL_SECONDS = 50
        private const val MAX_LEASE_MINUTES = 5L
    }
}
