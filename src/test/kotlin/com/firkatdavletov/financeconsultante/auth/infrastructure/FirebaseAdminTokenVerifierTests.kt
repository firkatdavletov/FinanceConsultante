package com.firkatdavletov.financeconsultante.auth.infrastructure

import com.firkatdavletov.financeconsultante.auth.application.FirebaseAuthenticationUnavailable
import com.firkatdavletov.financeconsultante.auth.application.InvalidFirebaseToken
import com.google.firebase.ErrorCode
import com.google.firebase.auth.AuthErrorCode
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseToken
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class FirebaseAdminTokenVerifierTests {
    private val auth = Mockito.mock(FirebaseAuth::class.java)
    private val verifier = FirebaseAdminTokenVerifier(auth)

    @Test fun `only SDK verified google claims become the identity and revocation checking is required`() {
        val token = Mockito.mock(FirebaseToken::class.java)
        Mockito.`when`(token.uid).thenReturn("synthetic-verified-uid")
        Mockito.`when`(token.name).thenReturn("Synthetic User")
        Mockito.`when`(token.email).thenReturn("user@example.test")
        Mockito.`when`(token.claims).thenReturn(mapOf("firebase" to mapOf("sign_in_provider" to "google.com")))
        Mockito.`when`(auth.verifyIdToken("synthetic-id-token", true)).thenReturn(token)
        val identity = verifier.verify("synthetic-id-token")
        assertThat(identity.uid).isEqualTo("synthetic-verified-uid")
        assertThat(identity.email).isEqualTo("user@example.test")
        Mockito.verify(auth).verifyIdToken("synthetic-id-token", true)
    }

    @Test fun `expired revoked disabled deleted and invalid tokens are rejected without leaking SDK data`() {
        listOf(
            AuthErrorCode.INVALID_ID_TOKEN,
            AuthErrorCode.EXPIRED_ID_TOKEN,
            AuthErrorCode.REVOKED_ID_TOKEN,
            AuthErrorCode.USER_DISABLED,
            AuthErrorCode.USER_NOT_FOUND,
            AuthErrorCode.TENANT_ID_MISMATCH,
        ).forEach { code ->
            val failure = FirebaseAuthException(ErrorCode.UNAUTHENTICATED, "Synthetic rejection", null, null, code)
            Mockito.doThrow(failure).`when`(auth).verifyIdToken("synthetic-rejected-token", true)
            assertThatThrownBy { verifier.verify("synthetic-rejected-token") }
                .isInstanceOf(InvalidFirebaseToken::class.java)
                .hasMessage("Invalid Firebase ID token")
                .hasNoCause()
        }
    }

    @Test fun `certificate or provider outage is unavailable rather than an invalid session`() {
        val failure =
            FirebaseAuthException(
                ErrorCode.UNAVAILABLE,
                "Synthetic outage",
                null,
                null,
                AuthErrorCode.CERTIFICATE_FETCH_FAILED,
            )
        Mockito.`when`(auth.verifyIdToken("synthetic-token", true)).thenThrow(failure)
        assertThatThrownBy { verifier.verify("synthetic-token") }
            .isInstanceOf(FirebaseAuthenticationUnavailable::class.java)
            .hasNoCause()
    }

    @Test fun `anonymous Firebase users cannot authenticate as Google accounts`() {
        val token = Mockito.mock(FirebaseToken::class.java)
        Mockito.`when`(token.claims).thenReturn(mapOf("firebase" to mapOf("sign_in_provider" to "anonymous")))
        Mockito.`when`(auth.verifyIdToken("synthetic-anonymous", true)).thenReturn(token)
        assertThatThrownBy { verifier.verify("synthetic-anonymous") }.isInstanceOf(InvalidFirebaseToken::class.java)
    }
}
