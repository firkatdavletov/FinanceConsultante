package com.firkatdavletov.financeconsultante.advice.application

import com.firkatdavletov.financeconsultante.advice.domain.PurchaseGoalImpact
import com.firkatdavletov.financeconsultante.advice.domain.PurchaseNecessity
import com.firkatdavletov.financeconsultante.advice.domain.PurchaseScenarioAssessment
import com.firkatdavletov.financeconsultante.analytics.domain.ForecastScenario
import com.firkatdavletov.financeconsultante.analytics.domain.SpendingEstimateSource
import com.firkatdavletov.financeconsultante.shared.domain.Money

@Suppress("TooManyFunctions") // Small render helpers keep each financial line explicit and independently reviewable.
object PurchaseAdviceMessages {
    fun render(advice: PurchaseAdvice): String =
        buildString {
            appendLine("Анализ покупки ${money(advice.request.amount)} на ${advice.request.purchaseOn}.")
            appendLine(assumptions(advice))
            appendLine("Купить в указанную дату:")
            appendLine(scenario("Базовый", advice.purchase.base))
            appendLine(scenario("Осторожный", advice.purchase.cautious))
            appendLine(postpone(advice))
            appendGoalImpact(advice)
            appendLine(recommendation(advice))
            append(
                "Анализ не записал расход и не изменил ledger. " +
                    if (advice.request.goalId == null) {
                        "Фактическую покупку запишите отдельно: /expense <сумма> <категория>."
                    } else {
                        "Фактическую покупку из цели запишите отдельно через /goals spend после проверки /goals."
                    },
            )
        }.take(TELEGRAM_LIMIT)

    private fun assumptions(advice: PurchaseAdvice): String {
        val date = if (advice.request.dateWasAssumed) "дата не указана — принято сегодня" else "дата указана"
        val necessity =
            when (advice.request.necessity) {
                PurchaseNecessity.NECESSARY -> "необходимая"
                PurchaseNecessity.OPTIONAL -> "необязательная"
                PurchaseNecessity.UNSPECIFIED -> "необходимость не указана"
            }
        val goal = advice.goalName?.let { "цель «${safeName(it)}»" } ?: "без связанной цели"
        val spending =
            when (advice.baseline.spending.source) {
                SpendingEstimateSource.HISTORICAL_AVERAGE -> {
                    "обычные траты ${money(advice.baseline.spending.baseDaily)}/день по истории"
                }

                SpendingEstimateSource.MANUAL -> {
                    "обычные траты ${money(advice.baseline.spending.baseDaily)}/день заданы вручную"
                }

                SpendingEstimateSource.UNAVAILABLE -> {
                    error("Incomplete advice must not be rendered")
                }
            }
        return "Допущения: $date; $necessity; $goal; $spending. Осторожный сценарий: " +
            "+${advice.baseline.cautiousSpendingPercent}% к обычным тратам и доходы на " +
            "${advice.baseline.cautiousIncomeDelayDays} дня позже."
    }

    private fun scenario(
        name: String,
        value: PurchaseScenarioAssessment,
    ): String =
        "$name: B после покупки ${money(value.cashAfterPurchase)}; будущие обязательства R " +
            "${money(value.remainingObligations)}; резерв E ${money(value.protectedReserve)}; " +
            "цели G ${money(value.goalReserve)}; доступно F ${money(value.freeAfterPurchase)}; " +
            "лимит ${money(value.dailyLimitAfterPurchase)}/день. Минимум денег " +
            "${money(value.minimumCashBalance)}, дефицит ${value.firstCashDeficitDate ?: "не ожидается"}, " +
            "затрагивание R/E/G ${value.firstReserveBreachDate ?: "не ожидается"}."

    private fun postpone(advice: PurchaseAdvice): String =
        "Отложить (не покупать до ${advice.baseline.endDate}): " +
            baseline("базовый", advice.baseline.base) + "; " +
            baseline("осторожный", advice.baseline.cautious) + "."

    private fun baseline(
        name: String,
        value: ForecastScenario,
    ): String =
        "$name минимум ${money(value.minimumDailyBalance)}, итог ${money(value.endingBalance)}, " +
            "дефицит ${value.firstDeficitDate ?: "не ожидается"}"

    private fun StringBuilder.appendGoalImpact(advice: PurchaseAdvice) {
        if (advice.request.goalId != null) {
            appendLine(
                "Из выделенных денег связанной цели в сценарии используется до " +
                    "${money(advice.purchase.cautious.goalFunding)}: B и G уменьшаются вместе, " +
                    "без двойного вычета из F.",
            )
        }
        val impacts = advice.purchase.cautious.goalImpacts
        if (impacts.isNotEmpty()) {
            appendLine("Влияние на цели: ${impacts.take(MAX_GOALS).joinToString("; ", transform = ::goalImpact)}.")
        } else {
            appendLine("Дополнительного дефицита целей в осторожном сценарии не рассчитано.")
        }
    }

    private fun recommendation(advice: PurchaseAdvice): String {
        val cautious = advice.purchase.cautious
        return when {
            cautious.affordable -> {
                "Рекомендация: по осторожному расчёту покупка посильна — после R/E/G остаётся " +
                    "${money(cautious.freeAfterPurchase)}, кассового разрыва и затрагивания резервов нет." +
                    necessityNote(advice, affordable = true)
            }

            advice.saferDate != null -> {
                "Рекомендация: отложить до ${advice.saferDate}. В указанную дату " + unsafeReasons(cautious) +
                    "; первая более безопасная дата найдена только по уже введённым планам." +
                    necessityNote(advice, affordable = false)
            }

            else -> {
                "Рекомендация: отложить — в ближайшие 30 дней безопасная дата не найдена. " +
                    unsafeReasons(cautious) + "." + necessityNote(advice, affordable = false)
            }
        }
    }

    private fun unsafeReasons(value: PurchaseScenarioAssessment): String {
        val reasons = mutableListOf<String>()
        if (value.freeAfterPurchase.amount.signum() < 0) {
            reasons += "после R/E/G не хватает ${money(Money.of(value.freeAfterPurchase.amount.negate()))}"
        }
        value.firstReserveBreachDate?.let { reasons += "резервы затрагиваются $it" }
        value.firstCashDeficitDate?.let { reasons += "денежный дефицит возникает $it" }
        if (value.goalImpacts.isNotEmpty()) reasons += "растёт дефицит целей"
        return reasons.distinct().joinToString("; ").ifEmpty { "запаса по осторожному сценарию недостаточно" }
    }

    private fun necessityNote(
        advice: PurchaseAdvice,
        affordable: Boolean,
    ): String =
        when (advice.request.necessity) {
            PurchaseNecessity.NECESSARY -> {
                if (affordable) {
                    " Покупка отмечена необходимой; числовые резервы всё равно сохранены."
                } else {
                    " Для необходимой покупки сначала измените сумму, дату или подтверждённые планы бюджета."
                }
            }

            PurchaseNecessity.OPTIONAL -> {
                if (affordable) {
                    " Покупка отмечена необязательной; сравните её с приоритетом целей."
                } else {
                    " Для необязательной покупки безопаснее сохранить отсрочку."
                }
            }

            PurchaseNecessity.UNSPECIFIED -> {
                " Необходимость не указана — это финансовая оценка, а не решение о приоритете."
            }
        }

    private fun goalImpact(value: PurchaseGoalImpact): String =
        "«${safeName(value.name)}» +${money(value.additionalShortfall)} нехватки"

    private fun safeName(value: String): String = value.replace(Regex("\\s+"), " ").take(MAX_GOAL_NAME)

    private fun money(value: Money): String = "${value.amount.toPlainString()} ${value.currency}"

    private const val MAX_GOALS = 5
    private const val MAX_GOAL_NAME = 40
    private const val TELEGRAM_LIMIT = 4096
}
