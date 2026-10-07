package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import com.firkatdavletov.financeconsultante.mobileapi.application.PlannedIncomeList
import com.firkatdavletov.financeconsultante.mobileapi.application.PlannedIncomeService
import com.firkatdavletov.financeconsultante.recurring.domain.ExpectedIncomeView
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

data class PlannedIncomeListResponse(
    val asOf: LocalDate,
    val items: List<PlannedIncomeResponse>,
) {
    companion object {
        fun from(incomes: PlannedIncomeList): PlannedIncomeListResponse =
            PlannedIncomeListResponse(
                incomes.asOf,
                incomes.items.map { PlannedIncomeResponse.from(it, incomes.asOf) },
            )
    }
}

data class PlannedIncomeResponse(
    val id: UUID,
    val category: PlannedIncomeCategoryResponse,
    val plannedAmount: ApiMoneyResponse,
    val dueOn: LocalDate,
    val overdue: Boolean,
    val status: String,
    val version: Long,
    val createdAt: Instant,
    val modifiedAt: Instant,
) {
    companion object {
        fun from(
            view: ExpectedIncomeView,
            asOf: LocalDate,
        ): PlannedIncomeResponse {
            val income = view.expectedIncome
            return PlannedIncomeResponse(
                income.id,
                PlannedIncomeCategoryResponse(income.categoryId, view.categoryName),
                ApiMoneyResponse(
                    income.plannedAmount.amount.toPlainString(),
                    income.plannedAmount.currency.name,
                ),
                income.dueOn,
                income.dueOn < asOf,
                income.status.name,
                income.version,
                income.createdAt,
                income.updatedAt,
            )
        }
    }
}

data class PlannedIncomeCategoryResponse(
    val id: UUID,
    val name: String,
)

@RestController
@RequestMapping("/api/v1/planned-incomes", produces = [MediaType.APPLICATION_JSON_VALUE])
@ConditionalOnProperty(prefix = "app.mobile-api", name = ["enabled"], havingValue = "true")
class PlannedIncomeController(
    private val plannedIncomes: PlannedIncomeService,
    properties: MobileApiProperties,
) {
    private val authenticator = MobileApiAuthenticator(properties)

    @GetMapping
    fun plannedIncomes(
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
    ): ResponseEntity<PlannedIncomeListResponse> {
        val telegramUserId = authenticator.authenticate(authorization)
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(PlannedIncomeListResponse.from(plannedIncomes.plannedIncomes(telegramUserId)))
    }
}
