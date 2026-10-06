package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@ConfigurationProperties("app.mobile-api")
class MobileApiProperties(
    val token: String = "",
    val telegramUserId: Long = 0,
) {
    fun validateEnabled() {
        require(token.length in MIN_TOKEN_LENGTH..MAX_TOKEN_LENGTH && token.none(Char::isWhitespace)) {
            "Mobile API token must contain $MIN_TOKEN_LENGTH-$MAX_TOKEN_LENGTH non-whitespace characters"
        }
        require(telegramUserId > 0) { "Mobile API Telegram user ID is required" }
    }

    companion object {
        private const val MIN_TOKEN_LENGTH = 32
        private const val MAX_TOKEN_LENGTH = 512
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MobileApiProperties::class)
class MobileApiPropertiesConfiguration

class InvalidMobileApiToken : IllegalArgumentException("Bearer token is missing or invalid")

class MobileApiAuthenticator(
    properties: MobileApiProperties,
) {
    private val expectedTokenHash: ByteArray
    private val telegramUserId: Long

    init {
        properties.validateEnabled()
        expectedTokenHash = hash(properties.token)
        telegramUserId = properties.telegramUserId
    }

    fun authenticate(authorization: String?): Long {
        val token =
            authorization
                ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
                ?.substring(BEARER_PREFIX.length)
                ?.takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }
                ?: throw InvalidMobileApiToken()
        if (!MessageDigest.isEqual(expectedTokenHash, hash(token))) throw InvalidMobileApiToken()
        return telegramUserId
    }

    private fun hash(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))

    companion object {
        private const val BEARER_PREFIX = "Bearer "
    }
}
