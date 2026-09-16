package com.firkatdavletov.financeconsultante.budget.application

import com.firkatdavletov.financeconsultante.budget.domain.BudgetState
import com.firkatdavletov.financeconsultante.budget.domain.DailyBudgetView
import com.firkatdavletov.financeconsultante.shared.domain.Money
import java.time.format.DateTimeFormatter
import java.util.Locale

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
            append("Бюджет на ${RUSSIAN_DATE.format(snapshot.date)}:\n")
            append("Баланс: ${money(checkNotNull(view.current.balance))}.\n")
            append("Обязательные расходы до конца месяца: ${money(view.current.remainingObligations)}.\n")
            append("Защищённый резерв: ${money(view.current.protectedReserve)}.\n")
            append("Цели: ${money(view.current.goalReserve)}.\n")
            append("Доступно сейчас: ${money(view.currentFreeAmount)}.\n\n")
            append("Дневной лимит: ${money(snapshot.originalDailyLimit)}.\n")
            append("Остаток лимита: ${money(view.remainingDailyLimit)}.\n\n")
            append("Вчерашний дневной расход (за исключением обязательных): ")
            append(money(view.spendingSummary.yesterday))
            append(comparisonEmoji(view.spendingSummary.yesterday, view.spendingSummary.lastThirtyDaysAverage))
            append("\nСредний ежедневный расход за последние 30 дней: ")
            append(money(view.spendingSummary.lastThirtyDaysAverage))
            append(comparisonEmoji(view.spendingSummary.lastThirtyDaysAverage, view.spendingSummary.yesterday))
            append("\n\n")
            if (snapshot.recalculated) append("Снимок пересчитан из-за изменившихся данных.\n\n")
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
                "Оценка: данных недостаточно.\nДействие: завершите настройку."
            }

            BudgetState.DEFICIT -> {
                "Оценка: дефицит ${money(Money.of(view.currentFreeAmount.amount.negate()))}; лимит 0.00 KZT.\n" +
                    "Действие: проверьте обязательства и сократите необязательные расходы."
            }

            BudgetState.NO_FREE_FUNDS -> {
                "Оценка: свободных средств нет.\nДействие: отложите необязательные расходы."
            }

            BudgetState.AVAILABLE -> {
                "Оценка: есть доступные средства ${money(view.currentFreeAmount)}.\n" +
                    "Действие: держите обычные траты в пределах остатка дневного лимита."
            }
        }

    private fun comparisonEmoji(
        value: Money,
        comparison: Money,
    ): String =
        when (value.amount.compareTo(comparison.amount)) {
            1 -> " ⬆️"
            -1 -> " ⬇️"
            else -> ""
        }

    private fun money(value: Money): String = "${value.amount.toPlainString()} ${value.currency}"

    private val RUSSIAN_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"))
}
