package com.firkatdavletov.financeconsultante.analytics.application

import com.firkatdavletov.financeconsultante.analytics.domain.BudgetForecast
import com.firkatdavletov.financeconsultante.analytics.domain.ForecastDay
import com.firkatdavletov.financeconsultante.analytics.domain.ForecastScenario
import com.firkatdavletov.financeconsultante.analytics.domain.GoalForecast
import com.firkatdavletov.financeconsultante.analytics.domain.GoalReachability
import com.firkatdavletov.financeconsultante.analytics.domain.SpendingEstimateSource
import com.firkatdavletov.financeconsultante.shared.domain.Money

object ForecastMessages {
    fun forecast(value: BudgetForecast): String {
        val text =
            buildString {
                appendLine(
                    "Прогноз ${value.startDate}–${value.endDate} " +
                        "(30 местных дней, включая сегодня).",
                )
                appendLine("Текущий учётный баланс B: ${money(value.currentBalance)}.")
                appendLine(spending(value))
                appendLine(
                    "Осторожный сценарий: " +
                        "обычные траты +${value.cautiousSpendingPercent}%; " +
                        "каждый ожидаемый доход сдвинут на " +
                        "${value.cautiousIncomeDelayDays} дня.",
                )
                appendLine(summary("Базовый", value.base))
                appendLine(summary("Осторожный", value.cautious))
                appendLine(
                    "Дата: +доход баз./ост.; −обяз.; −обычные баз./ост.; " +
                        "остаток баз./ост.",
                )
                value.base.days.zip(value.cautious.days).forEach { (base, cautious) ->
                    appendLine(day(base, cautious))
                }
                if (value.base.goals.isNotEmpty()) {
                    appendLine("Цели (достижимость базовый/осторожный):")
                    appendGoals(value.base.goals, value.cautious.goals)
                }
                append(
                    "Резерв целей и защищённый резерв " +
                        "${money(value.protectedReserve)} остаются внутри B " +
                        "и не повторяются " +
                        "как расход. Прогноз не меняет ledger.",
                )
            }
        return if (text.length <= TELEGRAM_LIMIT) text else text.take(TELEGRAM_LIMIT - TRUNCATED.length) + TRUNCATED
    }

    private fun spending(value: BudgetForecast): String =
        when (value.spending.source) {
            SpendingEstimateSource.HISTORICAL_AVERAGE -> {
                "Обычные траты: ${money(value.spending.baseDaily)}/день — среднее за " +
                    "${value.spending.historyDays} завершённых дней, включая нулевые; " +
                    "связанные обязательства исключены."
            }

            SpendingEstimateSource.MANUAL -> {
                "Обычные траты: ручной лимит ${money(value.spending.baseDaily)}/день."
            }

            SpendingEstimateSource.UNAVAILABLE -> {
                "Обычные траты не включены: история " +
                    "${value.spending.historyDays} дн., нужно минимум 14. " +
                    "Повторите с ручным лимитом: /forecast <KZT в день>."
            }
        }

    private fun summary(
        name: String,
        scenario: ForecastScenario,
    ): String =
        "$name: итог ${money(scenario.endingBalance)}, минимум ${money(scenario.minimumDailyBalance)}, " +
            "дефицит ${scenario.firstDeficitDate ?: "не ожидается"}."

    private fun day(
        base: ForecastDay,
        cautious: ForecastDay,
    ): String =
        "${base.date}: +${amount(base.expectedIncome)}/${amount(cautious.expectedIncome)}; " +
            "−${amount(base.obligations)}; " +
            "−${amount(base.variableSpending)}/${amount(cautious.variableSpending)}; " +
            "${amount(base.closingBalance)}/${amount(cautious.closingBalance)} KZT"

    private fun StringBuilder.appendGoals(
        base: List<GoalForecast>,
        cautious: List<GoalForecast>,
    ) {
        base.zip(cautious).take(MAX_GOALS).forEach { (usual, careful) ->
            appendLine(
                "«${safeName(usual.goal.name)}» до ${usual.goal.deadline}: " +
                    "${reachability(usual)}/${reachability(careful)}; " +
                    "не хватает ${amount(usual.shortfall)}/${amount(careful.shortfall)} KZT.",
            )
        }
        if (base.size > MAX_GOALS) {
            appendLine("Ещё целей: ${base.size - MAX_GOALS}; подробности — /goals.")
        }
    }

    private fun reachability(value: GoalForecast): String =
        when (value.reachability) {
            GoalReachability.ACHIEVED -> "достигнута"
            GoalReachability.REACHABLE -> "достижима по планам"
            GoalReachability.AT_RISK -> "под риском"
            GoalReachability.OVERDUE -> "срок прошёл"
            GoalReachability.OUTSIDE_HORIZON -> "за горизонтом 30 дней"
        }

    private fun safeName(value: String): String = value.replace(Regex("\\s+"), " ").take(MAX_GOAL_NAME)

    private fun money(value: Money): String = "${value.amount.toPlainString()} ${value.currency}"

    private fun amount(value: Money): String = value.amount.toPlainString()

    private const val TELEGRAM_LIMIT = 4096
    private const val MAX_GOALS = 5
    private const val MAX_GOAL_NAME = 40
    private const val TRUNCATED = "\nОтчёт сокращён до лимита Telegram."
}
