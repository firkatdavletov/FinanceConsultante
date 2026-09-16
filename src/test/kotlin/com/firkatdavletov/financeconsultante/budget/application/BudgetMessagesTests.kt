package com.firkatdavletov.financeconsultante.budget.application

import com.firkatdavletov.financeconsultante.budget.domain.BudgetEstimate
import com.firkatdavletov.financeconsultante.budget.domain.BudgetState
import com.firkatdavletov.financeconsultante.budget.domain.DailyBudgetSnapshot
import com.firkatdavletov.financeconsultante.budget.domain.DailyBudgetView
import com.firkatdavletov.financeconsultante.budget.domain.DailySpendingSummary
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class BudgetMessagesTests {
    @Test
    fun `morning report uses readable date sections and spending comparison`() {
        val report = BudgetMessages.daily(view("20000", "15000"), morning = true)

        assertThat(report).isEqualTo(
            """
            Доброе утро! Бюджет на 16 сентября:
            Баланс: 546116.50 KZT.
            Обязательные расходы до конца месяца: 266000.00 KZT.
            Защищённый резерв: 0.00 KZT.
            Цели: 0.00 KZT.
            Доступно сейчас: 280116.50 KZT.

            Дневной лимит: 18674.00 KZT.
            Остаток лимита: 18674.00 KZT.

            Вчерашний дневной расход (за исключением обязательных): 20000.00 KZT ⬆️
            Средний ежедневный расход за последние 30 дней: 15000.00 KZT ⬇️

            Оценка: есть доступные средства 280116.50 KZT.
            Действие: держите обычные траты в пределах остатка дневного лимита.
            Оценка основана только на введённых данных; ожидаемые доходы не считаются полученными. Добавляйте обязательства через /recurring.
            """.trimIndent(),
        )
    }

    @Test
    fun `equal spending values do not claim an increase or decrease`() {
        val report = BudgetMessages.daily(view("15000", "15000"))

        assertThat(report).contains(
            "Вчерашний дневной расход (за исключением обязательных): 15000.00 KZT\n",
            "Средний ежедневный расход за последние 30 дней: 15000.00 KZT\n",
        )
        assertThat(report).doesNotContain("⬆️", "⬇️")
    }

    private fun view(
        yesterday: String,
        average: String,
    ): DailyBudgetView {
        val date = LocalDate.parse("2026-09-16")
        val balance = Money.of("546116.50")
        val obligations = Money.of("266000")
        val reserve = Money.of("0")
        val goals = Money.of("0")
        val free = Money.of("280116.50")
        val limit = Money.of("18674")
        return DailyBudgetView(
            DailyBudgetSnapshot(
                UUID.fromString("43bcd255-03a0-46ee-b036-9bd8ca3e523a"),
                date,
                Instant.parse("2026-09-16T02:00:37.259022Z"),
                balance,
                obligations,
                reserve,
                goals,
                free,
                15,
                limit,
                0,
                0,
            ),
            BudgetEstimate(
                BudgetState.AVAILABLE,
                balance,
                obligations,
                reserve,
                goals,
                free,
                15,
                limit,
            ),
            Money.of("0"),
            limit,
            DailySpendingSummary(Money.of(yesterday), Money.of(average)),
        )
    }
}
