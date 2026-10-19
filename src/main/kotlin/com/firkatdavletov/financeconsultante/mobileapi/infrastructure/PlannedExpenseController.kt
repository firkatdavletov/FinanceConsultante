package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import com.firkatdavletov.financeconsultante.mobileapi.application.PlannedExpenseItem
import com.firkatdavletov.financeconsultante.mobileapi.application.PlannedExpenseList
import com.firkatdavletov.financeconsultante.mobileapi.application.PlannedExpenseMutationService
import com.firkatdavletov.financeconsultante.mobileapi.application.PlannedExpenseService
import com.firkatdavletov.financeconsultante.recurring.application.RecurringRuleView
import com.firkatdavletov.financeconsultante.recurring.domain.RecurringRule
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
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
    val rule: PlannedExpenseRuleReferenceResponse,
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
            item: PlannedExpenseItem,
            asOf: LocalDate,
        ): PlannedExpenseResponse {
            val view = item.expense
            val occurrence = view.occurrence
            return PlannedExpenseResponse(
                occurrence.id,
                PlannedExpenseRuleReferenceResponse.from(item.rule),
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

data class PlannedExpenseRuleReferenceResponse(
    val id: UUID,
    val frequency: String,
    val scheduleValue: Int,
    val startsOn: LocalDate,
    val active: Boolean,
    val version: Long,
) {
    companion object {
        fun from(rule: RecurringRule) =
            PlannedExpenseRuleReferenceResponse(
                rule.id,
                rule.frequency.name,
                rule.scheduleValue,
                rule.startsOn,
                rule.active,
                rule.version,
            )
    }
}

data class PlannedExpenseRuleResponse(
    val id: UUID,
    val category: PlannedExpenseCategoryResponse,
    val plannedAmount: ApiMoneyResponse,
    val frequency: String,
    val scheduleValue: Int,
    val startsOn: LocalDate,
    val active: Boolean,
    val version: Long,
    val createdAt: Instant,
    val modifiedAt: Instant,
) {
    companion object {
        fun from(view: RecurringRuleView): PlannedExpenseRuleResponse {
            val rule = view.rule
            return PlannedExpenseRuleResponse(
                rule.id,
                PlannedExpenseCategoryResponse(rule.categoryId, view.categoryName),
                ApiMoneyResponse(rule.amount.amount.toPlainString(), rule.amount.currency.name),
                rule.frequency.name,
                rule.scheduleValue,
                rule.startsOn,
                rule.active,
                rule.version,
                rule.createdAt,
                rule.updatedAt,
            )
        }
    }
}

data class CreatePlannedExpenseRequest(
    val categoryId: UUID? = null,
    val amount: String? = null,
    val frequency: String? = null,
    val scheduleValue: Int? = null,
    val startsOn: LocalDate? = null,
)

data class UpdatePlannedExpenseRequest(
    val version: Long? = null,
    val amount: String? = null,
)

data class ClosePlannedExpenseRequest(
    val version: Long? = null,
)

data class PlannedExpenseCategoryResponse(
    val id: UUID,
    val name: String,
)

@RestController
@RequestMapping("/api/v1/planned-expenses", produces = [MediaType.APPLICATION_JSON_VALUE])
@ConditionalOnProperty(prefix = "app.mobile-api", name = ["enabled"], havingValue = "true")
class PlannedExpenseController(
    private val plannedExpenses: PlannedExpenseService,
    private val mutations: PlannedExpenseMutationService,
    properties: MobileApiProperties,
    private val cloud: com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetService? = null,
) {
    private val authenticator = mobileAuthenticator(properties, cloud)

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

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun create(
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestBody request: CreatePlannedExpenseRequest,
    ): ResponseEntity<PlannedExpenseRuleResponse> {
        val telegramUserId = authenticator.authenticate(authorization)
        val created =
            mutations.create(
                telegramUserId,
                request.categoryId,
                request.amount,
                request.frequency,
                request.scheduleValue,
                request.startsOn,
            )
        return ResponseEntity
            .created(URI.create("/api/v1/planned-expenses/${created.rule.id}"))
            .cacheControl(CacheControl.noStore())
            .body(PlannedExpenseRuleResponse.from(created))
    }

    @PatchMapping("/{ruleId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun update(
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @PathVariable ruleId: UUID,
        @RequestBody request: UpdatePlannedExpenseRequest,
    ): ResponseEntity<PlannedExpenseRuleResponse> {
        val telegramUserId = authenticator.authenticate(authorization)
        val updated = mutations.update(telegramUserId, ruleId, request.version, request.amount)
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(PlannedExpenseRuleResponse.from(updated))
    }

    @DeleteMapping("/{ruleId}")
    fun delete(
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @PathVariable ruleId: UUID,
        @RequestParam(required = false) version: Long?,
    ): ResponseEntity<Void> {
        val telegramUserId = authenticator.authenticate(authorization)
        mutations.delete(telegramUserId, ruleId, version)
        return ResponseEntity
            .noContent()
            .cacheControl(CacheControl.noStore())
            .build()
    }

    @PostMapping("/{occurrenceId}/close", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun close(
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @PathVariable occurrenceId: UUID,
        @RequestBody request: ClosePlannedExpenseRequest,
    ): ResponseEntity<PlannedExpenseResponse> {
        val telegramUserId = authenticator.authenticate(authorization)
        val closed = mutations.close(telegramUserId, occurrenceId, request.version)
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(PlannedExpenseResponse.from(closed.item, closed.asOf))
    }
}
