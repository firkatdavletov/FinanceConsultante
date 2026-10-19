package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import com.firkatdavletov.financeconsultante.mobileapi.application.BudgetSyncBootstrap
import com.firkatdavletov.financeconsultante.mobileapi.application.DASHBOARD_SYNC_PROTOCOL
import com.firkatdavletov.financeconsultante.mobileapi.application.OfflineSyncService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v3/sync", produces = [MediaType.APPLICATION_JSON_VALUE])
@ConditionalOnProperty(prefix = "app.mobile-api", name = ["enabled"], havingValue = "true")
class DashboardSyncController(
    private val sync: OfflineSyncService,
    private val bootstrap: BudgetSyncBootstrap,
    properties: MobileApiProperties,
    private val cloud: com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetService? = null,
) {
    private val authenticator = mobileAuthenticator(properties, cloud)

    @GetMapping("/changes")
    fun changes(
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "200") limit: Int,
    ): ResponseEntity<com.firkatdavletov.financeconsultante.mobileapi.application.SyncPage> {
        val user = authenticator.authenticate(authorization)
        bootstrap.prepare(user, materialize = true)
        return ResponseEntity
            .ok()
            .cacheControl(
                CacheControl.noStore(),
            ).body(sync.changes(user, cursor, limit, DASHBOARD_SYNC_PROTOCOL))
    }
}
