package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import com.firkatdavletov.financeconsultante.ledger.application.OperationHistoryFilter
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.mobileapi.application.CreateOfflineOperation
import com.firkatdavletov.financeconsultante.mobileapi.application.CreateOfflineOperationRequest
import com.firkatdavletov.financeconsultante.mobileapi.application.OperationHistoryService
import com.firkatdavletov.financeconsultante.mobileapi.application.RecordedMobileOperation
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.format.annotation.DateTimeFormat
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
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/v1/operations", produces = [MediaType.APPLICATION_JSON_VALUE])
@ConditionalOnProperty(prefix = "app.mobile-api", name = ["enabled"], havingValue = "true")
class OperationHistoryController(
    private val history: OperationHistoryService,
    private val createOperation: CreateOfflineOperation,
    properties: MobileApiProperties,
    private val cloud: com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetService? = null,
) {
    private val authenticator = mobileAuthenticator(properties, cloud)

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun create(
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestBody request: CreateOfflineOperationRequest,
    ): ResponseEntity<RecordedMobileOperation> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            createOperation.create(authenticator.authenticate(authorization), request),
        )

    @GetMapping
    @Suppress("LongParameterList") // Explicit optional query parameters define the history HTTP contract.
    fun history(
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestParam(required = false) cursor: Long?,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) type: Direction?,
        @RequestParam(required = false) categoryId: UUID?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) dateFrom: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) dateTo: LocalDate?,
    ): ResponseEntity<OperationHistoryResponse> {
        val telegramUserId = authenticator.authenticate(authorization)
        val filter = OperationHistoryFilter(type, categoryId, dateFrom, dateTo)
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(OperationHistoryResponse.from(history.history(telegramUserId, cursor, limit, filter)))
    }
}
