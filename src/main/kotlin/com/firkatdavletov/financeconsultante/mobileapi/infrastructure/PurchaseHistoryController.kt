package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import com.firkatdavletov.financeconsultante.ledger.application.HistoryItem
import com.firkatdavletov.financeconsultante.ledger.application.OperationHistoryFilter
import com.firkatdavletov.financeconsultante.ledger.domain.DailyExchangeRate
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.mobileapi.application.InvalidCategoryRequest
import com.firkatdavletov.financeconsultante.mobileapi.application.InvalidOfflineRequest
import com.firkatdavletov.financeconsultante.mobileapi.application.InvalidOperationHistoryRequest
import com.firkatdavletov.financeconsultante.mobileapi.application.InvalidPlannedExpenseRequest
import com.firkatdavletov.financeconsultante.mobileapi.application.InvalidPlannedIncomeRequest
import com.firkatdavletov.financeconsultante.mobileapi.application.InvalidPlannedOperationRequest
import com.firkatdavletov.financeconsultante.mobileapi.application.InvalidPurchaseMutationRequest
import com.firkatdavletov.financeconsultante.mobileapi.application.MobileApiAccessDenied
import com.firkatdavletov.financeconsultante.mobileapi.application.MobileApiCategoryConflict
import com.firkatdavletov.financeconsultante.mobileapi.application.MobileApiCategoryNotFound
import com.firkatdavletov.financeconsultante.mobileapi.application.MobileApiOwnerRequired
import com.firkatdavletov.financeconsultante.mobileapi.application.MobileApiPlannedExpenseConflict
import com.firkatdavletov.financeconsultante.mobileapi.application.MobileApiPlannedExpenseNotFound
import com.firkatdavletov.financeconsultante.mobileapi.application.MobileApiPlannedExpenseOwnerRequired
import com.firkatdavletov.financeconsultante.mobileapi.application.MobileApiPlannedIncomeConflict
import com.firkatdavletov.financeconsultante.mobileapi.application.MobileApiPlannedIncomeNotFound
import com.firkatdavletov.financeconsultante.mobileapi.application.MobileApiPlannedIncomeOwnerRequired
import com.firkatdavletov.financeconsultante.mobileapi.application.MobileApiPurchaseConflict
import com.firkatdavletov.financeconsultante.mobileapi.application.MobileApiPurchaseNotFound
import com.firkatdavletov.financeconsultante.mobileapi.application.OfflineSyncConflict
import com.firkatdavletov.financeconsultante.mobileapi.application.OperationHistoryPage
import com.firkatdavletov.financeconsultante.mobileapi.application.OperationHistoryService
import com.firkatdavletov.financeconsultante.mobileapi.application.PurchaseMutationService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
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

data class OperationHistoryResponse(
    val items: List<OperationResponse>,
    val nextCursor: Long?,
    val totals: OperationTotalsResponse,
) {
    companion object {
        fun from(page: OperationHistoryPage): OperationHistoryResponse =
            OperationHistoryResponse(
                page.items.map(OperationResponse::from),
                page.nextCursor,
                OperationTotalsResponse.from(page.totals.expenses, page.totals.incomes),
            )
    }
}

data class OperationResponse(
    val id: UUID,
    val sequence: Long,
    val type: String,
    val category: OperationCategoryResponse,
    val originalAmount: ApiMoneyResponse,
    val amountKzt: ApiMoneyResponse,
    val occurredOn: LocalDate,
    val occurredAt: Instant,
    val createdAt: Instant,
    val modifiedAt: Instant,
    val status: String,
    val version: Long,
    val description: String?,
    val exchangeRate: OperationExchangeRateResponse?,
) {
    companion object {
        fun from(item: HistoryItem): OperationResponse =
            OperationResponse(
                id = item.entry.id,
                sequence = item.sequence,
                type = item.entry.direction.name,
                category = OperationCategoryResponse(item.entry.categoryId, item.category),
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
                exchangeRate = item.entry.exchangeRate?.let(OperationExchangeRateResponse::from),
            )

        private val API_ZONE = ZoneId.of("Asia/Almaty")
    }
}

data class OperationCategoryResponse(
    val id: UUID,
    val name: String,
)

data class ApiMoneyResponse(
    val amount: String,
    val currency: String,
)

data class OperationExchangeRateResponse(
    val rubKzt: String,
    val source: String,
    val quotedAt: Instant,
    val fetchedAt: Instant,
) {
    companion object {
        fun from(rate: DailyExchangeRate) =
            OperationExchangeRateResponse(
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
    val reason: String? = null,
)

data class UpdatePurchaseRequest(
    val version: Long? = null,
    val amount: String? = null,
)

@RestController
@RequestMapping("/api/v1/purchases", produces = [MediaType.APPLICATION_JSON_VALUE])
@ConditionalOnProperty(prefix = "app.mobile-api", name = ["enabled"], havingValue = "true")
class PurchaseHistoryController(
    private val history: OperationHistoryService,
    private val mutations: PurchaseMutationService,
    properties: MobileApiProperties,
) {
    private val authenticator = MobileApiAuthenticator(properties)

    @GetMapping
    fun history(
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestParam(required = false) cursor: Long?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): ResponseEntity<OperationHistoryResponse> {
        val telegramUserId = authenticator.authenticate(authorization)
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(
                OperationHistoryResponse.from(
                    history.history(telegramUserId, cursor, limit, OperationHistoryFilter(type = Direction.EXPENSE)),
                ),
            )
    }

    @PatchMapping("/{purchaseId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun updateAmount(
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @PathVariable purchaseId: UUID,
        @RequestBody request: UpdatePurchaseRequest,
    ): ResponseEntity<OperationResponse> {
        val telegramUserId = authenticator.authenticate(authorization)
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(
                OperationResponse.from(
                    mutations.updateAmount(telegramUserId, purchaseId, request.version, request.amount),
                ),
            )
    }

    @DeleteMapping("/{purchaseId}")
    fun refund(
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @PathVariable purchaseId: UUID,
        @RequestParam(required = false) version: Long?,
    ): ResponseEntity<Void> {
        val telegramUserId = authenticator.authenticate(authorization)
        mutations.refund(telegramUserId, purchaseId, version)
        return ResponseEntity
            .noContent()
            .cacheControl(CacheControl.noStore())
            .build()
    }
}

@RestControllerAdvice(
    assignableTypes = [
        CategoryController::class,
        DashboardController::class,
        PurchaseHistoryController::class,
        OperationHistoryController::class,
        OfflineSyncController::class,
        BudgetSyncController::class,
        DashboardSyncController::class,
        PlannedExpenseController::class,
        PlannedIncomeController::class,
        PlannedOperationController::class,
    ],
)
@ConditionalOnProperty(prefix = "app.mobile-api", name = ["enabled"], havingValue = "true")
@Suppress("TooManyFunctions") // One advice keeps the complete mobile API error contract consistent.
class MobileApiExceptionHandler {
    @ExceptionHandler(InvalidOfflineRequest::class)
    fun invalidOffline(error: InvalidOfflineRequest): ResponseEntity<MobileApiError> =
        ResponseEntity
            .badRequest()
            .cacheControl(CacheControl.noStore())
            .body(MobileApiError("invalid_operation", checkNotNull(error.message), error.reason))

    @ExceptionHandler(OfflineSyncConflict::class)
    fun syncConflict(error: OfflineSyncConflict): ResponseEntity<MobileApiError> =
        ResponseEntity
            .status(HttpStatus.CONFLICT)
            .cacheControl(CacheControl.noStore())
            .body(MobileApiError(error.code, "Operation or sync scope changed"))

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

    @ExceptionHandler(MobileApiOwnerRequired::class)
    fun ownerRequired(): ResponseEntity<MobileApiError> =
        ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .cacheControl(CacheControl.noStore())
            .body(MobileApiError("owner_required", "Category management requires OWNER membership"))

    @ExceptionHandler(MobileApiPlannedExpenseOwnerRequired::class)
    fun plannedExpenseOwnerRequired(): ResponseEntity<MobileApiError> =
        ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .cacheControl(CacheControl.noStore())
            .body(MobileApiError("owner_required", "Planned expense management requires OWNER membership"))

    @ExceptionHandler(InvalidCategoryRequest::class)
    fun invalidCategory(error: InvalidCategoryRequest): ResponseEntity<MobileApiError> =
        ResponseEntity
            .badRequest()
            .cacheControl(CacheControl.noStore())
            .body(MobileApiError("invalid_category", checkNotNull(error.message)))

    @ExceptionHandler(MobileApiCategoryNotFound::class)
    fun categoryNotFound(): ResponseEntity<MobileApiError> =
        ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .cacheControl(CacheControl.noStore())
            .body(MobileApiError("category_not_found", "Category is unavailable"))

    @ExceptionHandler(MobileApiCategoryConflict::class)
    fun categoryConflict(error: MobileApiCategoryConflict): ResponseEntity<MobileApiError> =
        ResponseEntity
            .status(HttpStatus.CONFLICT)
            .cacheControl(CacheControl.noStore())
            .body(MobileApiError("category_conflict", checkNotNull(error.message)))

    @ExceptionHandler(InvalidPlannedExpenseRequest::class)
    fun invalidPlannedExpense(error: InvalidPlannedExpenseRequest): ResponseEntity<MobileApiError> =
        ResponseEntity
            .badRequest()
            .cacheControl(CacheControl.noStore())
            .body(MobileApiError("invalid_planned_expense", checkNotNull(error.message)))

    @ExceptionHandler(MobileApiPlannedExpenseNotFound::class)
    fun plannedExpenseNotFound(): ResponseEntity<MobileApiError> =
        ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .cacheControl(CacheControl.noStore())
            .body(MobileApiError("planned_expense_not_found", "Planned expense is unavailable"))

    @ExceptionHandler(MobileApiPlannedExpenseConflict::class)
    fun plannedExpenseConflict(error: MobileApiPlannedExpenseConflict): ResponseEntity<MobileApiError> =
        ResponseEntity
            .status(HttpStatus.CONFLICT)
            .cacheControl(CacheControl.noStore())
            .body(MobileApiError("planned_expense_conflict", checkNotNull(error.message)))

    @ExceptionHandler(MobileApiPlannedIncomeOwnerRequired::class)
    fun plannedIncomeOwnerRequired(): ResponseEntity<MobileApiError> =
        ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .cacheControl(CacheControl.noStore())
            .body(MobileApiError("owner_required", "Planned income management requires OWNER membership"))

    @ExceptionHandler(InvalidPlannedIncomeRequest::class)
    fun invalidPlannedIncome(error: InvalidPlannedIncomeRequest): ResponseEntity<MobileApiError> =
        ResponseEntity
            .badRequest()
            .cacheControl(CacheControl.noStore())
            .body(MobileApiError("invalid_planned_income", checkNotNull(error.message)))

    @ExceptionHandler(MobileApiPlannedIncomeNotFound::class)
    fun plannedIncomeNotFound(): ResponseEntity<MobileApiError> =
        ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .cacheControl(CacheControl.noStore())
            .body(MobileApiError("planned_income_not_found", "Planned income is unavailable"))

    @ExceptionHandler(MobileApiPlannedIncomeConflict::class)
    fun plannedIncomeConflict(error: MobileApiPlannedIncomeConflict): ResponseEntity<MobileApiError> =
        ResponseEntity
            .status(HttpStatus.CONFLICT)
            .cacheControl(CacheControl.noStore())
            .body(MobileApiError("planned_income_conflict", checkNotNull(error.message)))

    @ExceptionHandler(InvalidOperationHistoryRequest::class, InvalidPlannedOperationRequest::class)
    fun invalidOperationRequest(error: IllegalArgumentException): ResponseEntity<MobileApiError> =
        ResponseEntity
            .badRequest()
            .cacheControl(CacheControl.noStore())
            .body(MobileApiError("invalid_request", checkNotNull(error.message)))

    @ExceptionHandler(InvalidPurchaseMutationRequest::class)
    fun invalidPurchase(error: InvalidPurchaseMutationRequest): ResponseEntity<MobileApiError> =
        ResponseEntity
            .badRequest()
            .cacheControl(CacheControl.noStore())
            .body(MobileApiError("invalid_purchase", checkNotNull(error.message)))

    @ExceptionHandler(MobileApiPurchaseNotFound::class)
    fun purchaseNotFound(): ResponseEntity<MobileApiError> =
        ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .cacheControl(CacheControl.noStore())
            .body(MobileApiError("purchase_not_found", "Purchase is unavailable"))

    @ExceptionHandler(MobileApiPurchaseConflict::class)
    fun purchaseConflict(error: MobileApiPurchaseConflict): ResponseEntity<MobileApiError> =
        ResponseEntity
            .status(HttpStatus.CONFLICT)
            .cacheControl(CacheControl.noStore())
            .body(MobileApiError("purchase_conflict", checkNotNull(error.message)))

    @ExceptionHandler(MethodArgumentTypeMismatchException::class, HttpMessageNotReadableException::class)
    fun invalidRequest(): ResponseEntity<MobileApiError> =
        ResponseEntity
            .badRequest()
            .cacheControl(CacheControl.noStore())
            .body(MobileApiError("invalid_request", "Request parameters or body are invalid"))
}
