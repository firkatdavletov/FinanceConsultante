package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MobileApiAuthenticatorTests {
    @Test
    fun `retired authentication never accepts any bearer credential`() {
        val authenticator = MobileApiAuthenticator()
        listOf(
            null,
            "",
            "synthetic-former-static-token-1234567890",
            "Bearer synthetic-former-static-token-1234567890",
            "bearer synthetic-former-static-token-1234567890",
            "Bearer firebase-looking-token",
        ).forEach {
            assertThatThrownBy { authenticator.authenticate(it) }.isInstanceOf(InvalidMobileApiToken::class.java)
        }
    }

    @Test
    fun `legacy factory cannot restore credential authentication`() {
        val authenticator = mobileAuthenticator(MobileApiProperties(991002), null)
        assertThatThrownBy { authenticator.authenticate("Bearer synthetic-former-static-token-1234567890") }
            .isInstanceOf(InvalidMobileApiToken::class.java)
    }
}
