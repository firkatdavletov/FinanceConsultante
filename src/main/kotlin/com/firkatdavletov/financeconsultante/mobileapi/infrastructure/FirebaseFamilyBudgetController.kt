package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import com.firkatdavletov.financeconsultante.auth.application.FirebaseAuthenticationService
import com.firkatdavletov.financeconsultante.mobileapi.application.AcceptFirebaseInvitation
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseFamilyBudgets
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@ConditionalOnProperty(name = ["app.firebase-auth.enabled", "app.mobile-api.enabled"], havingValue = "true")
class FirebaseFamilyBudgetController(
    private val authentication: FirebaseAuthenticationService,
    private val family: FirebaseFamilyBudgets,
) {
    @PostMapping("/api/v4/budgets/invitations/accept")
    fun join(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestBody request: AcceptFirebaseInvitation,
    ) = ResponseEntity
        .ok()
        .cacheControl(
            CacheControl.noStore(),
        ).body(family.join(authentication.authenticate(authorization).firebaseUid, request))

    @GetMapping("/api/v4/budgets/{budgetId}/members")
    fun members(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @PathVariable budgetId: UUID,
    ) = ResponseEntity
        .ok()
        .cacheControl(
            CacheControl.noStore(),
        ).body(family.members(authentication.authenticate(authorization).firebaseUid, budgetId))

    @DeleteMapping("/api/v4/budgets/{budgetId}/members/{memberId}")
    fun revoke(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @PathVariable budgetId: UUID,
        @PathVariable memberId: UUID,
    ): ResponseEntity<Void> {
        family.revoke(authentication.authenticate(authorization).firebaseUid, budgetId, memberId)
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }
}
