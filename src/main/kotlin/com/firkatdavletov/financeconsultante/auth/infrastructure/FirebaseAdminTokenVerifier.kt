package com.firkatdavletov.financeconsultante.auth.infrastructure

import com.firkatdavletov.financeconsultante.auth.application.FirebaseAuthenticationUnavailable
import com.firkatdavletov.financeconsultante.auth.application.FirebaseIdTokenVerifier
import com.firkatdavletov.financeconsultante.auth.application.InvalidFirebaseToken
import com.firkatdavletov.financeconsultante.auth.application.VerifiedFirebaseIdentity
import com.google.firebase.auth.AuthErrorCode
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseToken

class FirebaseAdminTokenVerifier(
    private val auth: FirebaseAuth,
    private val shutdown: () -> Unit = {},
) : FirebaseIdTokenVerifier,
    AutoCloseable {
    override fun verify(idToken: String): VerifiedFirebaseIdentity {
        val decoded = verifyWithSdk(idToken)
        val provider = (decoded.claims["firebase"] as? Map<*, *>)?.get("sign_in_provider")
        if (provider != "google.com") throw InvalidFirebaseToken()
        return VerifiedFirebaseIdentity(decoded.uid, decoded.name, decoded.email)
    }

    private fun verifyWithSdk(idToken: String): FirebaseToken =
        try {
            // true also checks revoked tokens, deleted users and disabled accounts.
            auth.verifyIdToken(idToken, true)
        } catch (error: FirebaseAuthException) {
            when (error.authErrorCode) {
                AuthErrorCode.INVALID_ID_TOKEN, AuthErrorCode.EXPIRED_ID_TOKEN,
                AuthErrorCode.REVOKED_ID_TOKEN, AuthErrorCode.USER_DISABLED,
                AuthErrorCode.USER_NOT_FOUND, AuthErrorCode.TENANT_ID_MISMATCH,
                -> throw InvalidFirebaseToken()

                else -> throw FirebaseAuthenticationUnavailable()
            }
        } catch (_: IllegalArgumentException) {
            throw InvalidFirebaseToken()
        }

    override fun close() = shutdown()
}
