package com.firkatdavletov.financeconsultante.auth.infrastructure

import com.firkatdavletov.financeconsultante.auth.application.EmailNotVerified
import com.firkatdavletov.financeconsultante.auth.application.FirebaseAuthenticationUnavailable
import com.firkatdavletov.financeconsultante.auth.application.FirebaseIdTokenVerifier
import com.firkatdavletov.financeconsultante.auth.application.InvalidFirebaseToken
import com.firkatdavletov.financeconsultante.auth.application.VerifiedFirebaseIdentity
import com.google.firebase.auth.AuthErrorCode
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseToken
import java.time.Instant

class FirebaseAdminTokenVerifier(
    private val auth: FirebaseAuth,
    private val shutdown: () -> Unit = {},
) : FirebaseIdTokenVerifier,
    AutoCloseable {
    override fun verify(idToken: String): VerifiedFirebaseIdentity = verifyIdentity(idToken, false)

    override fun verifyForDeletion(idToken: String): VerifiedFirebaseIdentity = verifyIdentity(idToken, true)

    private fun verifyIdentity(
        idToken: String,
        deletion: Boolean,
    ): VerifiedFirebaseIdentity {
        val decoded = verifyWithSdk(idToken)
        val provider = (decoded.claims["firebase"] as? Map<*, *>)?.get("sign_in_provider")
        when (provider) {
            "google.com" -> {
                Unit
            }

            "password" -> {
                if (deletion) {
                    if (decoded.email.isNullOrBlank()) throw InvalidFirebaseToken()
                } else {
                    validatePasswordEmail(decoded)
                }
            }

            else -> {
                throw InvalidFirebaseToken()
            }
        }
        return VerifiedFirebaseIdentity(
            decoded.uid,
            decoded.name,
            decoded.email,
            authenticationTime(decoded.claims["auth_time"]),
        )
    }

    private fun authenticationTime(value: Any?): Instant? {
        if (value !is Number) return null
        // The SDK JSON parser uses BigDecimal for untyped numeric claims.
        return runCatching {
            Instant.ofEpochSecond(
                java.math.BigDecimal(value.toString()).longValueExact(),
            )
        }.getOrNull()
    }

    private fun validatePasswordEmail(decoded: FirebaseToken) {
        if (decoded.email.isNullOrBlank()) throw InvalidFirebaseToken()
        if (decoded.claims["email_verified"] != true) throw EmailNotVerified()
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
