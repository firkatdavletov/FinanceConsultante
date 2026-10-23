package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import com.firkatdavletov.financeconsultante.auth.application.FirebaseAuthenticationService
import com.firkatdavletov.financeconsultante.auth.application.FirebaseAuthenticationUnavailable
import com.firkatdavletov.financeconsultante.auth.application.InvalidFirebaseToken
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetCommand
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetFailure
import com.firkatdavletov.financeconsultante.mobileapi.application.CreateFirebaseBudget
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseBudgetBindingUnavailable
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseBudgetCreation
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseBudgets
import com.firkatdavletov.financeconsultante.mobileapi.application.ImportFirebaseBudget
import com.firkatdavletov.financeconsultante.mobileapi.application.MobileApiAccessDenied
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.UUID

@RestController
@RequestMapping("/api/v4/budgets")
@ConditionalOnProperty(name = ["app.firebase-auth.enabled", "app.mobile-api.enabled"], havingValue = "true")
class FirebaseBudgetController(
    private val authentication: FirebaseAuthenticationService,
    private val budgets: FirebaseBudgets,
    private val creation: FirebaseBudgetCreation,
) {
    @PostMapping
    fun create(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestBody request: CreateFirebaseBudget,
    ) = noStore(creation.create(authentication.authenticate(authorization).firebaseUid, request))

    @PostMapping("/import")
    fun importLocal(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestBody request: ImportFirebaseBudget,
    ) = noStore(creation.importLocal(authentication.authenticate(authorization).firebaseUid, request))

    @GetMapping
    fun list(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
    ) = noStore(budgets.list(authentication.authenticate(authorization).firebaseUid))

    @GetMapping("/{budgetId}")
    fun budget(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @PathVariable budgetId: UUID,
    ) = noStore(budgets.budget(authentication.authenticate(authorization).firebaseUid, budgetId))

    @GetMapping("/{budgetId}/sync/changes")
    fun changes(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @PathVariable budgetId: UUID,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "200") limit: Int,
    ) = noStore(budgets.changes(authentication.authenticate(authorization).firebaseUid, budgetId, cursor, limit))

    @PostMapping("/{budgetId}/sync/commands")
    fun command(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @PathVariable budgetId: UUID,
        @RequestBody command: CloudBudgetCommand,
    ) = noStore(budgets.command(authentication.authenticate(authorization).firebaseUid, budgetId, command))

    private fun <T> noStore(value: T) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value)
}

@RestControllerAdvice(
    assignableTypes = [
        FirebaseBudgetController::class,
        FirebaseBudgetInvitationController::class,
        FirebaseFamilyBudgetController::class,
    ],
)
class FirebaseBudgetErrors {
    @ExceptionHandler(InvalidFirebaseToken::class)
    fun unauthorized() =
        ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
            .cacheControl(CacheControl.noStore())
            .body(mapOf("code" to "unauthorized"))

    @ExceptionHandler(FirebaseAuthenticationUnavailable::class, FirebaseBudgetBindingUnavailable::class)
    fun unavailable() = error(HttpStatus.SERVICE_UNAVAILABLE, "server_unavailable")

    @ExceptionHandler(MobileApiAccessDenied::class)
    fun denied() = error(HttpStatus.FORBIDDEN, "access_denied")

    @ExceptionHandler(CloudBudgetFailure::class)
    fun conflict(failure: CloudBudgetFailure) = error(HttpStatus.CONFLICT, failure.code)

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid() = error(HttpStatus.BAD_REQUEST, "invalid_request")

    private fun error(
        status: HttpStatus,
        code: String,
    ) = ResponseEntity
        .status(status)
        .cacheControl(CacheControl.noStore())
        .body(mapOf("code" to code))
}
