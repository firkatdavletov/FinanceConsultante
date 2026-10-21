package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetCommand
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetFailure
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetService
import com.firkatdavletov.financeconsultante.mobileapi.application.CreateCloudBudget
import com.firkatdavletov.financeconsultante.mobileapi.application.MobileApiAccessDenied
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.security.MessageDigest

@RestController
@RequestMapping("/api/v4")
@ConditionalOnProperty(prefix = "app.mobile-api", name = ["enabled"], havingValue = "true")
class CloudBudgetController(
    private val service: CloudBudgetService,
    private val properties: MobileApiProperties,
) {
    init {
        properties.validateEnabled()
    }

    @GetMapping("/budget")
    fun budget(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
    ) = noStore(service.find(authenticate(authorization)))

    @PostMapping("/legacy/budgets")
    fun create(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestBody request: CreateCloudBudget,
    ) = noStore(service.create(authenticate(authorization), request))

    @GetMapping("/sync/changes")
    fun changes(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "200") limit: Int,
    ) = noStore(service.changes(authenticate(authorization), cursor, limit))

    @PostMapping("/sync/commands")
    fun command(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestBody command: CloudBudgetCommand,
    ) = noStore(service.command(authenticate(authorization), command))

    private fun authenticate(authorization: String?): String {
        val token =
            authorization?.takeIf { it.startsWith("Bearer ", true) }?.substring("Bearer ".length)
                ?: throw InvalidMobileApiToken()
        if (!MessageDigest.isEqual(hash(token), hash(properties.token))) throw InvalidMobileApiToken()
        return hash(token).joinToString("") { "%02x".format(it) }
    }

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    private fun <T> noStore(value: T) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value)
}

@RestControllerAdvice(assignableTypes = [CloudBudgetController::class])
class CloudBudgetErrors {
    @ExceptionHandler(InvalidMobileApiToken::class)
    fun unauthorized(): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
            mapOf("code" to "unauthorized"),
        )

    @ExceptionHandler(MobileApiAccessDenied::class)
    fun denied(): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("code" to "access_denied"))

    @ExceptionHandler(CloudBudgetFailure::class)
    fun failure(error: CloudBudgetFailure): ResponseEntity<Map<String, String>> =
        ResponseEntity
            .status(
                if (error.code ==
                    "budget_not_found"
                ) {
                    HttpStatus.NOT_FOUND
                } else {
                    HttpStatus.CONFLICT
                },
            ).body(mapOf("code" to error.code))

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(): ResponseEntity<Map<String, String>> =
        ResponseEntity.badRequest().body(
            mapOf("code" to "invalid_request", "reason" to "invalid_request"),
        )
}
