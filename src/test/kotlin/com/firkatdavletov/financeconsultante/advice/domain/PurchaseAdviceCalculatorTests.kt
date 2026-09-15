package com.firkatdavletov.financeconsultante.advice.domain

import com.firkatdavletov.financeconsultante.analytics.domain.BudgetForecastCalculator
import com.firkatdavletov.financeconsultante.analytics.domain.BudgetForecastInput
import com.firkatdavletov.financeconsultante.analytics.domain.ForecastCashFlow
import com.firkatdavletov.financeconsultante.analytics.domain.ForecastCashFlowType
import com.firkatdavletov.financeconsultante.analytics.domain.ForecastGoal
import com.firkatdavletov.financeconsultante.analytics.domain.ForecastPurchase
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

@Suppress("MagicNumber")
class PurchaseAdviceCalculatorTests {
    private val start = LocalDate.parse("2026-09-06")

    @Test
    fun `goal funded purchase reduces balance and allocation without double subtraction`() {
        val goalId = UUID.randomUUID()
        val input =
            input(
                balance = "1000",
                reserve = "100",
                goals =
                    listOf(
                        ForecastGoal(
                            goalId,
                            "Ноутбук",
                            Money.of("500"),
                            Money.of("500"),
                            start.plusDays(10),
                            1,
                            emptyList(),
                        ),
                    ),
            )
        val baseline = BudgetForecastCalculator.calculate(input)
        val purchased =
            BudgetForecastCalculator.calculate(
                input.copy(purchase = ForecastPurchase(start, Money.of("500"), goalId)),
            )

        val assessment = PurchaseAdviceCalculator.assess(baseline, purchased, start).cautious

        assertThat(assessment.cashAfterPurchase).isEqualTo(Money.of("500"))
        assertThat(assessment.goalFunding).isEqualTo(Money.of("500"))
        assertThat(assessment.goalReserve).isEqualTo(Money.of("0"))
        assertThat(assessment.freeAfterPurchase).isEqualTo(Money.of("400"))
        assertThat(assessment.affordable).isTrue()
        assertThat(assessment.goalImpacts.single().additionalShortfall).isEqualTo(Money.of("500"))
    }

    @Test
    fun `future income cannot make an immediate purchase affordable before it arrives`() {
        val input =
            input(
                balance = "100",
                flows =
                    listOf(
                        ForecastCashFlow(
                            start.plusDays(4),
                            ForecastCashFlowType.EXPECTED_INCOME,
                            Money.of("1000"),
                        ),
                    ),
            )
        val baseline = BudgetForecastCalculator.calculate(input)
        val purchased =
            BudgetForecastCalculator.calculate(
                input.copy(purchase = ForecastPurchase(start, Money.of("500"))),
            )

        val assessment = PurchaseAdviceCalculator.assess(baseline, purchased, start)

        assertThat(assessment.base.firstCashDeficitDate).isEqualTo(start)
        assertThat(assessment.cautious.firstCashDeficitDate).isEqualTo(start)
        assertThat(assessment.cautious.affordable).isFalse()
    }

    @Test
    fun `future obligations remain reserved in the daily limit after purchase`() {
        val input =
            input(
                balance = "1000",
                reserve = "100",
                flows =
                    listOf(
                        ForecastCashFlow(
                            start.plusDays(2),
                            ForecastCashFlowType.OBLIGATION,
                            Money.of("300"),
                        ),
                    ),
            )
        val baseline = BudgetForecastCalculator.calculate(input)
        val purchased =
            BudgetForecastCalculator.calculate(
                input.copy(purchase = ForecastPurchase(start, Money.of("400"))),
            )

        val assessment = PurchaseAdviceCalculator.assess(baseline, purchased, start).base

        assertThat(assessment.cashAfterPurchase).isEqualTo(Money.of("600"))
        assertThat(assessment.remainingObligations).isEqualTo(Money.of("300"))
        assertThat(assessment.freeAfterPurchase).isEqualTo(Money.of("200"))
        assertThat(assessment.dailyLimitAfterPurchase).isEqualTo(Money.of("8"))
    }

    private fun input(
        balance: String,
        reserve: String = "0",
        flows: List<ForecastCashFlow> = emptyList(),
        goals: List<ForecastGoal> = emptyList(),
    ): BudgetForecastInput =
        BudgetForecastInput(
            start,
            Money.of(balance),
            Money.of(reserve),
            historyDays = 30,
            historicalVariableSpending = Money.of("0"),
            cashFlows = flows,
            goals = goals,
        )
}
