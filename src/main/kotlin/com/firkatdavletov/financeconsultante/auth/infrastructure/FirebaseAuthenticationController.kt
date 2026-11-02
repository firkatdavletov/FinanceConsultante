package com.firkatdavletov.financeconsultante.auth.infrastructure

import com.firkatdavletov.financeconsultante.auth.application.EmailNotVerified
import com.firkatdavletov.financeconsultante.auth.application.FirebaseAuthenticationService
import com.firkatdavletov.financeconsultante.auth.application.FirebaseAuthenticationUnavailable
import com.firkatdavletov.financeconsultante.auth.application.InvalidFirebaseToken
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestController
@ConditionalOnProperty(prefix = "app.firebase-auth", name = ["enabled"], havingValue = "true")
class FirebaseAuthenticationController(
    private val service: FirebaseAuthenticationService,
) {
    @PostMapping("/api/v4/auth/firebase")
    fun authenticate(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
    ) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.authenticate(authorization))
}

@RestControllerAdvice(assignableTypes = [FirebaseAuthenticationController::class])
class FirebaseAuthenticationErrors {
    @ExceptionHandler(EmailNotVerified::class)
    fun emailNotVerified(): ResponseEntity<Map<String, String>> =
        ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .cacheControl(CacheControl.noStore())
            .body(mapOf("code" to "email_not_verified"))

    @ExceptionHandler(InvalidFirebaseToken::class)
    fun unauthorized(): ResponseEntity<Map<String, String>> =
        ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
            .cacheControl(CacheControl.noStore())
            .body(mapOf("code" to "unauthorized"))

    @ExceptionHandler(FirebaseAuthenticationUnavailable::class)
    fun unavailable(): ResponseEntity<Map<String, String>> =
        ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .cacheControl(CacheControl.noStore())
            .body(mapOf("code" to "auth_unavailable"))
}
