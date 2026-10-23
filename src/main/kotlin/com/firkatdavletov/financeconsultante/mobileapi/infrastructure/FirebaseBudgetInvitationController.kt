package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import com.firkatdavletov.financeconsultante.auth.application.FirebaseAuthenticationService
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseBudgetInvitations
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v4/budgets/{budgetId}/invitations")
@ConditionalOnProperty(name = ["app.firebase-auth.enabled", "app.mobile-api.enabled"], havingValue = "true")
class FirebaseBudgetInvitationController(
    private val authentication: FirebaseAuthenticationService,
    private val invitations: FirebaseBudgetInvitations,
) {
    @GetMapping("/current")
    fun current(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @PathVariable budgetId: UUID,
    ) = noStore(invitations.current(authentication.authenticate(authorization).firebaseUid, budgetId))

    @PostMapping
    fun create(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @PathVariable budgetId: UUID,
    ) = noStore(invitations.create(authentication.authenticate(authorization).firebaseUid, budgetId))

    @DeleteMapping("/{invitationId}")
    fun revoke(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @PathVariable budgetId: UUID,
        @PathVariable invitationId: UUID,
    ): ResponseEntity<Void> {
        invitations.revoke(authentication.authenticate(authorization).firebaseUid, budgetId, invitationId)
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }

    private fun <T> noStore(value: T) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value)
}
