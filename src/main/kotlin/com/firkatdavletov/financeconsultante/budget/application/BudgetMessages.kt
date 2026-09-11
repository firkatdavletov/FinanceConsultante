package com.firkatdavletov.financeconsultante.budget.application

import com.firkatdavletov.financeconsultante.budget.domain.BudgetState
import com.firkatdavletov.financeconsultante.budget.domain.DailyBudgetView
import com.firkatdavletov.financeconsultante.shared.domain.Money
import com.firkatdavletov.financeconsultante.telegram.domain.ACCOUNTING_ZONE

object BudgetMessages {
    fun daily(
        view: DailyBudgetView,
        morning: Boolean = false,
    ): String {
        val snapshot = view.snapshot
        val currentState =
            when (view.currentFreeAmount.amount.signum()) {
                -1 -> BudgetState.DEFICIT
                0 -> BudgetState.NO_FREE_FUNDS
                else -> BudgetState.AVAILABLE
            }
        return buildString {
            if (morning) append("Доброе утро! ")
            val localAsOf = snapshot.asOf.atZone(ACCOUNTING_ZONE).toLocalTime()
            append("Бюджет на ${snapshot.date} (срез $localAsOf Asia/Almaty):\n")
            append("Учётный баланс: ${money(checkNotNull(view.current.balance))}.\n")
            append("Обязательства до конца месяца и просроченные: ${money(view.current.remainingObligations)}.\n")
            append("Защищённый резерв: ${money(view.current.protectedReserve)}. ")
            append("Цели: ${money(view.current.goalReserve)}.\n")
            append("Доступно сейчас: ${money(view.currentFreeAmount)}. ")
            append("Дней с сегодняшним: ${view.current.daysRemaining}.\n")
            append("Дневной лимит: ${money(snapshot.originalDailyLimit)}. ")
            append("Остаток лимита: ${money(view.remainingDailyLimit)}.")
            if (view.spentAfterSnapshot.amount.signum() > 0) {
                append(" После среза обычные траты: ${money(view.spentAfterSnapshot)}.")
            }
            append("\n")
            if (snapshot.recalculated) append("Снимок пересчитан из-за изменившихся данных.\n")
            append(assessment(currentState, view))
            append("\nОценка основана только на введённых данных; ожидаемые доходы не считаются полученными. ")
            append("Добавляйте обязательства через /recurring.")
        }
    }

    fun incomplete(): String =
        "Данных недостаточно для оценки бюджета: завершите настройку начального остатка, даты, времени отчёта " +
            "и резерва через /start. Лимит не рассчитан."

    fun skipped(date: java.time.LocalDate): String =
        "Утренний отчёт за $date пропущен: приложение восстановилось после окна догоняющей отправки 12:00 " +
            "Asia/Almaty. Текущую оценку можно запросить командой /today."

    private fun assessment(
        state: BudgetState,
        view: DailyBudgetView,
    ): String =
        when (state) {
            BudgetState.INCOMPLETE -> {
                "Оценка: данных недостаточно. Завершите настройку."
            }

            BudgetState.DEFICIT -> {
                "Оценка: дефицит ${money(Money.of(view.currentFreeAmount.amount.negate()))}; лимит 0.00 KZT. " +
                    "Действие: проверьте обязательства и сократите необязательные расходы."
            }

            BudgetState.NO_FREE_FUNDS -> {
                "Оценка: свободных средств нет. Действие: отложите необязательные расходы."
            }

            BudgetState.AVAILABLE -> {
                "Оценка: есть доступные средства ${money(view.currentFreeAmount)}. " +
                    "Действие: держите обычные траты в пределах остатка дневного лимита."
            }
        }

    private fun money(value: Money): String = "${value.amount.toPlainString()} ${value.currency}"
}
