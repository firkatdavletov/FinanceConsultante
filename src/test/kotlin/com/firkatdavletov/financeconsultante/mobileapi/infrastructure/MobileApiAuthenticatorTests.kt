package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MobileApiAuthenticatorTests {
    @Test
    fun `accepts only the configured bearer token`() {
        val authenticator = MobileApiAuthenticator(MobileApiProperties(TOKEN, TELEGRAM_USER_ID))

        assertThat(authenticator.authenticate("Bearer $TOKEN")).isEqualTo(TELEGRAM_USER_ID)
        assertThat(authenticator.authenticate("bearer $TOKEN")).isEqualTo(TELEGRAM_USER_ID)
        listOf(null, "", TOKEN, "Bearer wrong", "Bearer $TOKEN extra").forEach { authorization ->
            assertThatThrownBy { authenticator.authenticate(authorization) }
                .isInstanceOf(InvalidMobileApiToken::class.java)
        }
    }

    @Test
    fun `enabled configuration requires a strong token and positive user id`() {
        assertThatIllegalArgumentException().isThrownBy {
            MobileApiAuthenticator(MobileApiProperties("short", TELEGRAM_USER_ID))
        }
        assertThatIllegalArgumentException().isThrownBy {
            MobileApiAuthenticator(MobileApiProperties(TOKEN, 0))
        }
    }

    companion object {
        private const val TOKEN = "synthetic-mobile-token-1234567890"
        private const val TELEGRAM_USER_ID = 991002L
    }
}
