package com.firkatdavletov.financeconsultante.auth.infrastructure

import com.firkatdavletov.financeconsultante.auth.application.FirebaseAuthenticationService
import com.firkatdavletov.financeconsultante.auth.application.FirebaseAuthenticationUnavailable
import com.firkatdavletov.financeconsultante.auth.application.InvalidFirebaseToken
import com.firkatdavletov.financeconsultante.auth.application.ProfileDeletionFailure
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.dao.DataAccessException
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Clock
import java.time.Duration
import java.util.Base64

@RestController
@ConditionalOnProperty(prefix = "app.firebase-auth", name = ["enabled"], havingValue = "true")
class ProfileDeletionController(
    private val authentication: FirebaseAuthenticationService,
    private val deletion: JdbcProfileDeletion,
    private val clock: Clock,
) {
    @DeleteMapping("/api/v4/profile")
    fun delete(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestHeader("X-Deletion-Token", required = false) token: String?,
    ): ResponseEntity<Map<String, String>> {
        val hash = tokenHash(token)
        deletion.status(hash)?.let { return response(it) }
        val identity = authentication.identityForDeletion(authorization)
        val authTime =
            identity.authTime ?: throw ProfileDeletionFailure("recent_login_required", HttpStatus.FORBIDDEN.value())
        val age = Duration.between(authTime, clock.instant())
        if (age.isNegative ||
            age > Duration.ofMinutes(RECENT_LOGIN_MINUTES)
        ) {
            throw ProfileDeletionFailure("recent_login_required", HttpStatus.FORBIDDEN.value())
        }
        return response(deletion.accept(identity.uid, hash))
    }

    @GetMapping("/api/v4/profile/deletion")
    fun status(
        @RequestHeader("X-Deletion-Token", required = false) token: String?,
    ): ResponseEntity<Map<String, String>> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(
                mapOf(
                    "status" to
                        (
                            deletion.status(tokenHash(token))
                                ?: throw ProfileDeletionFailure("deletion_not_found", HttpStatus.NOT_FOUND.value())
                        ),
                ),
            )

    private fun response(status: String): ResponseEntity<Map<String, String>> =
        ResponseEntity
            .status(if (status == "PENDING") HttpStatus.ACCEPTED else HttpStatus.OK)
            .cacheControl(CacheControl.noStore())
            .body(mapOf("status" to status))

    private fun tokenHash(token: String?): String {
        if (token == null || !Regex("[A-Za-z0-9_-]{43}").matches(token)) invalidToken()
        val bytes = Base64.getUrlDecoder().decode(token)
        if (bytes.size != SECRET_BYTES ||
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) != token
        ) {
            invalidToken()
        }
        return deletionHash(token)
    }

    private companion object {
        const val RECENT_LOGIN_MINUTES = 5L
        const val SECRET_BYTES = 32
    }

    private fun invalidToken(): Nothing =
        throw ProfileDeletionFailure("invalid_deletion_token", HttpStatus.BAD_REQUEST.value())
}

@RestControllerAdvice(assignableTypes = [ProfileDeletionController::class])
class ProfileDeletionErrors {
    @ExceptionHandler(ProfileDeletionFailure::class)
    fun failure(error: ProfileDeletionFailure) = response(error.status, error.code)

    @ExceptionHandler(InvalidFirebaseToken::class)
    fun unauthorized() =
        ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
            .cacheControl(CacheControl.noStore())
            .body(mapOf("code" to "unauthorized"))

    @ExceptionHandler(FirebaseAuthenticationUnavailable::class)
    fun authUnavailable() = response(HttpStatus.SERVICE_UNAVAILABLE.value(), "auth_unavailable")

    @ExceptionHandler(DataAccessException::class)
    fun unavailable() = response(HttpStatus.SERVICE_UNAVAILABLE.value(), "deletion_unavailable")

    private fun response(
        status: Int,
        code: String,
    ) = ResponseEntity
        .status(status)
        .cacheControl(CacheControl.noStore())
        .body(mapOf("code" to code))
}
