package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/** Retained only for explicit import of historical Telegram-backed data; never a credential. */
@ConfigurationProperties("app.mobile-api")
class MobileApiProperties(
    val telegramUserId: Long = 0,
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MobileApiProperties::class)
class MobileApiPropertiesConfiguration

class InvalidMobileApiToken : IllegalArgumentException("Legacy mobile authentication has been retired")

/** Old unscoped endpoints are closed even when an obsolete token property is supplied. */
class MobileApiAuthenticator {
    @Suppress("UNUSED_PARAMETER")
    fun authenticate(authorization: String?): Long = throw InvalidMobileApiToken()
}

@Suppress("UNUSED_PARAMETER")
fun mobileAuthenticator(
    properties: MobileApiProperties,
    cloud: com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetService?,
): MobileApiAuthenticator = MobileApiAuthenticator()
