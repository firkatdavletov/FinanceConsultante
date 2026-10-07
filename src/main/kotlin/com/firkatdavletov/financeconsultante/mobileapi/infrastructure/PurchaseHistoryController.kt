package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import com.firkatdavletov.financeconsultante.ledger.application.HistoryItem
import com.firkatdavletov.financeconsultante.ledger.domain.DailyExchangeRate
import com.firkatdavletov.financeconsultante.mobileapi.application.InvalidPurchaseHistoryRequest
import com.firkatdavletov.financeconsultante.mobileapi.application.MobileApiAccessDenied
import com.firkatdavletov.financeconsultante.mobileapi.application.PurchaseHistoryPage
import com.firkatdavletov.financeconsultante.mobileapi.application.PurchaseHistoryService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class PurchaseHistoryResponse(
    val items: List<PurchaseResponse>,
    val nextCursor: Long?,
) {
    companion object {
        fun from(page: PurchaseHistoryPage): PurchaseHistoryResponse =
            PurchaseHistoryResponse(page.items.map(PurchaseResponse::from), page.nextCursor)
    }
}

data class PurchaseResponse(
    val id: UUID,
    val sequence: Long,
    val category: PurchaseCategoryResponse,
    val originalAmount: ApiMoneyResponse,
    val amountKzt: ApiMoneyResponse,
    val occurredOn: LocalDate,
    val occurredAt: Instant,
    val createdAt: Instant,
    val modifiedAt: Instant,
    val status: String,
    val version: Long,
    val description: String?,
    val exchangeRate: PurchaseExchangeRateResponse?,
) {
    companion object {
        fun from(item: HistoryItem): PurchaseResponse =
            PurchaseResponse(
                id = item.entry.id,
                sequence = item.sequence,
                category = PurchaseCategoryResponse(item.entry.categoryId, item.category),
                originalAmount =
                    ApiMoneyResponse(
                        item.entry.originalAmount.amount
                            .toPlainString(),
                        item.entry.originalAmount.currency.name,
                    ),
                amountKzt =
                    ApiMoneyResponse(
                        item.entry.amountKzt.amount
                            .toPlainString(),
                        "KZT",
                    ),
                occurredOn =
                    item.entry.occurredAt
                        .atZone(API_ZONE)
                        .toLocalDate(),
                occurredAt = item.entry.occurredAt,
                createdAt = item.entry.createdAt,
                modifiedAt = item.entry.modifiedAt,
                status = item.entry.status.name,
                version = item.entry.version,
                description = item.entry.normalizedDescription,
                exchangeRate = item.entry.exchangeRate?.let(PurchaseExchangeRateResponse::from),
            )

        private val API_ZONE = ZoneId.of("Asia/Almaty")
    }
}

data class PurchaseCategoryResponse(
    val id: UUID,
    val name: String,
)

data class ApiMoneyResponse(
    val amount: String,
    val currency: String,
)

data class PurchaseExchangeRateResponse(
    val rubKzt: String,
    val source: String,
    val quotedAt: Instant,
    val fetchedAt: Instant,
) {
    companion object {
        fun from(rate: DailyExchangeRate) =
            PurchaseExchangeRateResponse(
                rate.rubKzt.toPlainString(),
                rate.source,
                rate.quotedAt,
                rate.fetchedAt,
            )
    }
}

data class MobileApiError(
    val code: String,
    val message: String,
)

@RestController
@RequestMapping("/api/v1/purchases", produces = [MediaType.APPLICATION_JSON_VALUE])
@ConditionalOnProperty(prefix = "app.mobile-api", name = ["enabled"], havingValue = "true")
class PurchaseHistoryController(
    private val history: PurchaseHistoryService,
    properties: MobileApiProperties,
) {
    private val authenticator = MobileApiAuthenticator(properties)

    @GetMapping
    fun history(
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestParam(required = false) cursor: Long?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): ResponseEntity<PurchaseHistoryResponse> {
        val telegramUserId = authenticator.authenticate(authorization)
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(PurchaseHistoryResponse.from(history.history(telegramUserId, cursor, limit)))
    }
}

@RestControllerAdvice(
    assignableTypes = [
        CategoryController::class,
        PurchaseHistoryController::class,
        PlannedExpenseController::class,
        PlannedIncomeController::class,
    ],
)
@ConditionalOnProperty(prefix = "app.mobile-api", name = ["enabled"], havingValue = "true")
class MobileApiExceptionHandler {
    @ExceptionHandler(InvalidMobileApiToken::class)
    fun unauthorized(): ResponseEntity<MobileApiError> =
        ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"mobile-api\"")
            .cacheControl(CacheControl.noStore())
            .body(MobileApiError("unauthorized", "Bearer token is missing or invalid"))

    @ExceptionHandler(MobileApiAccessDenied::class)
    fun forbidden(): ResponseEntity<MobileApiError> =
        ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .cacheControl(CacheControl.noStore())
            .body(MobileApiError("access_denied", "Active budget membership is required"))

    @ExceptionHandler(InvalidPurchaseHistoryRequest::class, MethodArgumentTypeMismatchException::class)
    fun invalidRequest(): ResponseEntity<MobileApiError> =
        ResponseEntity
            .badRequest()
            .cacheControl(CacheControl.noStore())
            .body(MobileApiError("invalid_request", "cursor and limit must be valid positive numbers"))
}
