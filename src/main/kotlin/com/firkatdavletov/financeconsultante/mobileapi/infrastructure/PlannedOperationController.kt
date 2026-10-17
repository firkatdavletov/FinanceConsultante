package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.mobileapi.application.PlannedExpenseItem
import com.firkatdavletov.financeconsultante.mobileapi.application.PlannedOperationFilter
import com.firkatdavletov.financeconsultante.mobileapi.application.PlannedOperationItem
import com.firkatdavletov.financeconsultante.mobileapi.application.PlannedOperationList
import com.firkatdavletov.financeconsultante.mobileapi.application.PlannedOperationPeriod
import com.firkatdavletov.financeconsultante.mobileapi.application.PlannedOperationService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class PlannedOperationListResponse(
    val asOf: LocalDate,
    val dateFrom: LocalDate,
    val dateTo: LocalDate,
    val items: List<PlannedOperationResponse>,
    val totals: OperationTotalsResponse,
) {
    companion object {
        fun from(plans: PlannedOperationList) =
            PlannedOperationListResponse(
                plans.asOf,
                plans.dateFrom,
                plans.dateTo,
                plans.items.map { PlannedOperationResponse.from(it, plans.asOf) },
                OperationTotalsResponse.from(plans.totals.expenses, plans.totals.incomes),
            )
    }
}

data class PlannedOperationResponse(
    val id: UUID,
    val type: String,
    val category: OperationCategoryResponse,
    val plannedAmount: ApiMoneyResponse,
    val paidAmount: ApiMoneyResponse,
    val remainingAmount: ApiMoneyResponse,
    val dueOn: LocalDate,
    val overdue: Boolean,
    val status: String,
    val version: Long,
    val createdAt: Instant,
    val modifiedAt: Instant,
    val rule: PlannedExpenseRuleReferenceResponse?,
) {
    companion object {
        fun from(
            item: PlannedOperationItem,
            asOf: LocalDate,
        ): PlannedOperationResponse =
            when (item) {
                is PlannedOperationItem.Expense -> {
                    val expense = PlannedExpenseResponse.from(PlannedExpenseItem(item.view, item.rule), asOf)
                    PlannedOperationResponse(
                        expense.id,
                        Direction.EXPENSE.name,
                        OperationCategoryResponse(expense.category.id, expense.category.name),
                        expense.plannedAmount,
                        expense.paidAmount,
                        expense.remainingAmount,
                        expense.dueOn,
                        expense.overdue,
                        expense.status,
                        expense.version,
                        expense.createdAt,
                        expense.modifiedAt,
                        expense.rule,
                    )
                }

                is PlannedOperationItem.Income -> {
                    val income = PlannedIncomeResponse.from(item.view, asOf)
                    PlannedOperationResponse(
                        income.id,
                        Direction.INCOME.name,
                        OperationCategoryResponse(income.category.id, income.category.name),
                        income.plannedAmount,
                        ApiMoneyResponse("0.00", "KZT"),
                        income.plannedAmount,
                        income.dueOn,
                        income.overdue,
                        income.status,
                        income.version,
                        income.createdAt,
                        income.modifiedAt,
                        null,
                    )
                }
            }
    }
}

@RestController
@RequestMapping("/api/v1/planned-operations", produces = [MediaType.APPLICATION_JSON_VALUE])
@ConditionalOnProperty(prefix = "app.mobile-api", name = ["enabled"], havingValue = "true")
class PlannedOperationController(
    private val plans: PlannedOperationService,
    properties: MobileApiProperties,
) {
    private val authenticator = MobileApiAuthenticator(properties)

    @GetMapping
    fun plannedOperations(
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestParam(required = false) type: Direction?,
        @RequestParam(defaultValue = "END_OF_MONTH") period: PlannedOperationPeriod,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) dateFrom: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) dateTo: LocalDate?,
    ): ResponseEntity<PlannedOperationListResponse> {
        val telegramUserId = authenticator.authenticate(authorization)
        val filter = PlannedOperationFilter(type, period, dateFrom, dateTo)
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(PlannedOperationListResponse.from(plans.plannedOperations(telegramUserId, filter)))
    }
}
