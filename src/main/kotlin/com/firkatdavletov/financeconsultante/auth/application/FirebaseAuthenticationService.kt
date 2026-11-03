package com.firkatdavletov.financeconsultante.auth.application

import java.time.Instant
import java.util.UUID

data class VerifiedFirebaseIdentity(
    val uid: String,
    val displayName: String?,
    val email: String?,
    val authTime: Instant? = null,
)

data class AuthenticatedUser(
    val id: UUID,
    val firebaseUid: String,
    val displayName: String?,
    val email: String?,
)

fun interface FirebaseIdTokenVerifier {
    fun verify(idToken: String): VerifiedFirebaseIdentity

    fun verifyForDeletion(idToken: String): VerifiedFirebaseIdentity = verify(idToken)
}

fun interface FirebaseUserStore {
    fun upsert(identity: VerifiedFirebaseIdentity): AuthenticatedUser
}

class InvalidFirebaseToken : RuntimeException("Invalid Firebase ID token")

class EmailNotVerified : RuntimeException("Firebase email is not verified")

class FirebaseAuthenticationUnavailable : RuntimeException("Firebase authentication is unavailable")

class FirebaseAuthenticationService(
    private val verifier: FirebaseIdTokenVerifier,
    private val users: FirebaseUserStore,
) {
    fun authenticate(authorization: String?): AuthenticatedUser = users.upsert(verifier.verify(token(authorization)))

    fun identityForDeletion(authorization: String?): VerifiedFirebaseIdentity =
        verifier.verifyForDeletion(token(authorization))

    private fun token(authorization: String?): String {
        val token =
            authorization
                ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
                ?.substring(BEARER_PREFIX.length)
                ?.takeIf { it.length in 1..MAX_TOKEN_LENGTH && it.none(Char::isWhitespace) }
                ?: throw InvalidFirebaseToken()
        // Only verified claims reach storage. Neither UID nor profile fields come from a request body.
        return token
    }

    companion object {
        private const val BEARER_PREFIX = "Bearer "
        private const val MAX_TOKEN_LENGTH = 16384
    }
}
