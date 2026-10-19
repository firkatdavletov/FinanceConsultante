package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import com.firkatdavletov.financeconsultante.mobileapi.application.DashboardBudgetExecution
import com.firkatdavletov.financeconsultante.mobileapi.application.DashboardBudgetProgress
import com.firkatdavletov.financeconsultante.mobileapi.application.DashboardCashFlow
import com.firkatdavletov.financeconsultante.mobileapi.application.DashboardExpenseCategory
import com.firkatdavletov.financeconsultante.mobileapi.application.DashboardForecast
import com.firkatdavletov.financeconsultante.mobileapi.application.DashboardForecastScenario
import com.firkatdavletov.financeconsultante.mobileapi.application.DashboardFreeMoney
import com.firkatdavletov.financeconsultante.mobileapi.application.DashboardMonthActuals
import com.firkatdavletov.financeconsultante.mobileapi.application.DashboardPeriod
import com.firkatdavletov.financeconsultante.mobileapi.application.DashboardService
import com.firkatdavletov.financeconsultante.mobileapi.application.DashboardSummary
import com.firkatdavletov.financeconsultante.mobileapi.application.DashboardUpcomingPayment
import com.firkatdavletov.financeconsultante.shared.domain.Money
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

data class DashboardResponse(
    val asOf: Instant,
    val date: LocalDate,
    val period: DashboardPeriodResponse,
    val availableMoney: ApiMoneyResponse,
    val freeMoney: DashboardFreeMoneyResponse,
    val monthActuals: DashboardMonthActualsResponse,
    val cashFlow: DashboardCashFlowResponse,
    val budgetExecution: DashboardBudgetExecutionResponse,
    val forecast: DashboardForecastResponse,
    val expenseStructure: DashboardExpenseStructureResponse,
    val upcomingPayments: List<DashboardUpcomingPaymentResponse>,
) {
    companion object {
        fun from(summary: DashboardSummary): DashboardResponse =
            DashboardResponse(
                summary.asOf,
                summary.date,
                DashboardPeriodResponse.from(summary.period),
                money(summary.availableMoney),
                DashboardFreeMoneyResponse.from(summary.freeMoney),
                DashboardMonthActualsResponse.from(summary.monthActuals),
                DashboardCashFlowResponse.from(summary.cashFlow),
                DashboardBudgetExecutionResponse.from(summary.budgetExecution),
                DashboardForecastResponse.from(summary.forecast),
                DashboardExpenseStructureResponse(
                    money(summary.expenseStructure.shareBasisAmount),
                    summary.expenseStructure.categories.map(DashboardExpenseCategoryResponse::from),
                ),
                summary.upcomingPayments.map(DashboardUpcomingPaymentResponse::from),
            )
    }
}

data class DashboardPeriodResponse(
    val startInclusive: LocalDate,
    val endInclusive: LocalDate,
    val elapsedPercent: String,
) {
    companion object {
        fun from(period: DashboardPeriod) =
            DashboardPeriodResponse(period.startInclusive, period.endInclusive, period.elapsedPercent.toPlainString())
    }
}

data class DashboardFreeMoneyResponse(
    val amount: ApiMoneyResponse,
    val state: String,
    val remainingObligations: ApiMoneyResponse,
    val protectedReserve: ApiMoneyResponse,
    val goalReserve: ApiMoneyResponse,
    val reservedTotal: ApiMoneyResponse,
    val daysRemaining: Int,
    val safeDailyLimit: ApiMoneyResponse,
) {
    companion object {
        fun from(value: DashboardFreeMoney) =
            DashboardFreeMoneyResponse(
                money(value.amount),
                value.state.name,
                money(value.remainingObligations),
                money(value.protectedReserve),
                money(value.goalReserve),
                money(value.reservedTotal),
                value.daysRemaining,
                money(value.safeDailyLimit),
            )
    }
}

data class DashboardMonthActualsResponse(
    val income: ApiMoneyResponse,
    val expenses: ApiMoneyResponse,
    val refunds: ApiMoneyResponse,
    val netExpenses: ApiMoneyResponse,
) {
    companion object {
        fun from(value: DashboardMonthActuals) =
            DashboardMonthActualsResponse(
                money(value.income),
                money(value.expenses),
                money(value.refunds),
                money(value.netExpenses),
            )
    }
}

data class DashboardCashFlowResponse(
    val income: ApiMoneyResponse,
    val expenses: ApiMoneyResponse,
    val refunds: ApiMoneyResponse,
    val otherOutflows: ApiMoneyResponse,
    val net: ApiMoneyResponse,
) {
    companion object {
        fun from(value: DashboardCashFlow) =
            DashboardCashFlowResponse(
                money(value.income),
                money(value.expenses),
                money(value.refunds),
                money(value.otherOutflows),
                money(value.net),
            )
    }
}

data class DashboardBudgetExecutionResponse(
    val basis: String,
    val total: DashboardBudgetProgressResponse,
    val variable: DashboardBudgetProgressResponse,
) {
    companion object {
        fun from(value: DashboardBudgetExecution) =
            DashboardBudgetExecutionResponse(
                value.basis.name,
                DashboardBudgetProgressResponse.from(value.total),
                DashboardBudgetProgressResponse.from(value.variable),
            )
    }
}

data class DashboardBudgetProgressResponse(
    val spent: ApiMoneyResponse,
    val plannedAmount: ApiMoneyResponse,
    val usedPercent: String?,
    val paceStatus: String,
) {
    companion object {
        fun from(value: DashboardBudgetProgress) =
            DashboardBudgetProgressResponse(
                money(value.spent),
                money(value.plannedAmount),
                value.usedPercent?.toPlainString(),
                value.paceStatus.name,
            )
    }
}

data class DashboardForecastResponse(
    val throughInclusive: LocalDate,
    val estimateSource: String,
    val historyDays: Int,
    val baseDailySpending: ApiMoneyResponse,
    val cautiousDailySpending: ApiMoneyResponse,
    val base: DashboardForecastScenarioResponse,
    val cautious: DashboardForecastScenarioResponse,
) {
    companion object {
        fun from(value: DashboardForecast) =
            DashboardForecastResponse(
                value.throughInclusive,
                value.estimateSource.name,
                value.historyDays,
                money(value.baseDailySpending),
                money(value.cautiousDailySpending),
                DashboardForecastScenarioResponse.from(value.base),
                DashboardForecastScenarioResponse.from(value.cautious),
            )
    }
}

data class DashboardForecastScenarioResponse(
    val expectedIncome: ApiMoneyResponse,
    val expectedObligations: ApiMoneyResponse,
    val expectedVariableExpenses: ApiMoneyResponse,
    val expectedExpenses: ApiMoneyResponse,
    val endingBalance: ApiMoneyResponse,
) {
    companion object {
        fun from(value: DashboardForecastScenario) =
            DashboardForecastScenarioResponse(
                money(value.expectedIncome),
                money(value.expectedObligations),
                money(value.expectedVariableExpenses),
                money(value.expectedExpenses),
                money(value.endingBalance),
            )
    }
}

data class DashboardExpenseStructureResponse(
    val shareBasisAmount: ApiMoneyResponse,
    val categories: List<DashboardExpenseCategoryResponse>,
)

data class DashboardExpenseCategoryResponse(
    val categoryId: UUID,
    val name: String,
    val amount: ApiMoneyResponse,
    val sharePercent: String,
) {
    companion object {
        fun from(value: DashboardExpenseCategory) =
            DashboardExpenseCategoryResponse(
                value.categoryId,
                value.name,
                money(value.amount),
                value.sharePercent.toPlainString(),
            )
    }
}

data class DashboardUpcomingPaymentResponse(
    val occurrenceId: UUID,
    val category: OperationCategoryResponse,
    val dueOn: LocalDate,
    val remainingAmount: ApiMoneyResponse,
    val overdue: Boolean,
) {
    companion object {
        fun from(value: DashboardUpcomingPayment) =
            DashboardUpcomingPaymentResponse(
                value.occurrenceId,
                OperationCategoryResponse(value.categoryId, value.categoryName),
                value.dueOn,
                money(value.remainingAmount),
                value.overdue,
            )
    }
}

@RestController
@RequestMapping("/api/v1/dashboard", produces = [MediaType.APPLICATION_JSON_VALUE])
@ConditionalOnProperty(prefix = "app.mobile-api", name = ["enabled"], havingValue = "true")
class DashboardController(
    private val dashboard: DashboardService,
    properties: MobileApiProperties,
    private val cloud: com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetService? = null,
) {
    private val authenticator = mobileAuthenticator(properties, cloud)

    @GetMapping
    fun dashboard(
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
    ): ResponseEntity<DashboardResponse> {
        val telegramUserId = authenticator.authenticate(authorization)
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(DashboardResponse.from(dashboard.dashboard(telegramUserId)))
    }
}

private fun money(value: Money) = ApiMoneyResponse(value.amount.toPlainString(), value.currency.name)
