package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import com.firkatdavletov.financeconsultante.mobileapi.application.PlannedExpenseList
import com.firkatdavletov.financeconsultante.mobileapi.application.PlannedExpenseService
import com.firkatdavletov.financeconsultante.recurring.domain.RecurringOccurrenceView
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class PlannedExpenseListResponse(
    val asOf: LocalDate,
    val throughInclusive: LocalDate,
    val items: List<PlannedExpenseResponse>,
) {
    companion object {
        fun from(expenses: PlannedExpenseList): PlannedExpenseListResponse =
            PlannedExpenseListResponse(
                expenses.asOf,
                expenses.throughInclusive,
                expenses.items.map { PlannedExpenseResponse.from(it, expenses.asOf) },
            )
    }
}

data class PlannedExpenseResponse(
    val id: UUID,
    val category: PlannedExpenseCategoryResponse,
    val plannedAmount: ApiMoneyResponse,
    val paidAmount: ApiMoneyResponse,
    val remainingAmount: ApiMoneyResponse,
    val dueOn: LocalDate,
    val overdue: Boolean,
    val status: String,
    val version: Long,
    val createdAt: Instant,
    val modifiedAt: Instant,
) {
    companion object {
        fun from(
            view: RecurringOccurrenceView,
            asOf: LocalDate,
        ): PlannedExpenseResponse {
            val occurrence = view.occurrence
            return PlannedExpenseResponse(
                occurrence.id,
                PlannedExpenseCategoryResponse(occurrence.categoryId, view.categoryName),
                ApiMoneyResponse(
                    occurrence.plannedAmount.amount.toPlainString(),
                    occurrence.plannedAmount.currency.name,
                ),
                ApiMoneyResponse(view.paidAmount.amount.toPlainString(), view.paidAmount.currency.name),
                ApiMoneyResponse(view.remainingAmount.amount.toPlainString(), view.remainingAmount.currency.name),
                occurrence.dueOn,
                view.overdue(asOf),
                occurrence.status.name,
                occurrence.version,
                occurrence.createdAt,
                occurrence.updatedAt,
            )
        }
    }
}

data class PlannedExpenseCategoryResponse(
    val id: UUID,
    val name: String,
)

@RestController
@RequestMapping("/api/v1/planned-expenses", produces = [MediaType.APPLICATION_JSON_VALUE])
@ConditionalOnProperty(prefix = "app.mobile-api", name = ["enabled"], havingValue = "true")
class PlannedExpenseController(
    private val plannedExpenses: PlannedExpenseService,
    properties: MobileApiProperties,
) {
    private val authenticator = MobileApiAuthenticator(properties)

    @GetMapping
    fun plannedExpenses(
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
    ): ResponseEntity<PlannedExpenseListResponse> {
        val telegramUserId = authenticator.authenticate(authorization)
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(PlannedExpenseListResponse.from(plannedExpenses.plannedExpenses(telegramUserId)))
    }
}
