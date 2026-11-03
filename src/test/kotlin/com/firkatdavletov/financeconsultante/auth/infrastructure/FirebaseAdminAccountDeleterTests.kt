package com.firkatdavletov.financeconsultante.auth.infrastructure

import com.firkatdavletov.financeconsultante.auth.application.FirebaseAuthenticationUnavailable
import com.google.firebase.ErrorCode
import com.google.firebase.auth.AuthErrorCode
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class FirebaseAdminAccountDeleterTests {
    private val auth = Mockito.mock(FirebaseAuth::class.java)
    private val deleter = FirebaseAdminAccountDeleter { auth }

    @Test fun `deletion uses verified UID and missing user is successful on retry`() {
        deleter.delete("synthetic-user")
        Mockito.verify(auth).deleteUser("synthetic-user")
        Mockito.doThrow(failure(AuthErrorCode.USER_NOT_FOUND)).`when`(auth).deleteUser("synthetic-user")
        deleter.delete("synthetic-user")
    }

    @Test fun `SDK errors become safe retryable failures`() {
        Mockito.doThrow(failure(AuthErrorCode.CERTIFICATE_FETCH_FAILED)).`when`(auth).deleteUser("synthetic-user")
        assertThatThrownBy { deleter.delete("synthetic-user") }
            .isInstanceOf(FirebaseAuthenticationUnavailable::class.java)
            .hasNoCause()
    }

    private fun failure(code: AuthErrorCode) =
        FirebaseAuthException(ErrorCode.UNKNOWN, "Synthetic failure", null, null, code)
}
