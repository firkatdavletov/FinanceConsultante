package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import com.firkatdavletov.financeconsultante.mobileapi.application.BudgetSyncBootstrap
import com.firkatdavletov.financeconsultante.mobileapi.application.BudgetSyncCommand
import com.firkatdavletov.financeconsultante.mobileapi.application.BudgetSyncCommands
import com.firkatdavletov.financeconsultante.mobileapi.application.OfflineSyncService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v2/sync", produces = [MediaType.APPLICATION_JSON_VALUE])
@ConditionalOnProperty(prefix = "app.mobile-api", name = ["enabled"], havingValue = "true")
class BudgetSyncController(
    private val sync: OfflineSyncService,
    private val commands: BudgetSyncCommands,
    private val bootstrap: BudgetSyncBootstrap,
    properties: MobileApiProperties,
) {
    private val authenticator = MobileApiAuthenticator(properties)

    @GetMapping("/changes")
    fun changes(
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "200") limit: Int,
    ): ResponseEntity<com.firkatdavletov.financeconsultante.mobileapi.application.SyncPage> {
        val user = authenticator.authenticate(authorization)
        bootstrap.prepare(user)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sync.changes(user, cursor, limit, 2))
    }

    @PostMapping("/commands")
    fun command(
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestBody command: BudgetSyncCommand,
    ) = ResponseEntity
        .ok()
        .cacheControl(CacheControl.noStore())
        .body(commands.execute(authenticator.authenticate(authorization), command))
}
