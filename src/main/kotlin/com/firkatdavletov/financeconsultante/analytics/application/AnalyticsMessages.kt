package com.firkatdavletov.financeconsultante.analytics.application

import com.firkatdavletov.financeconsultante.analytics.domain.AnalyticsChange
import com.firkatdavletov.financeconsultante.analytics.domain.AnalyticsPeriod
import com.firkatdavletov.financeconsultante.analytics.domain.AnalyticsReport
import com.firkatdavletov.financeconsultante.analytics.domain.CategoryAnalytics
import com.firkatdavletov.financeconsultante.shared.domain.ACCOUNTING_ZONE
import com.firkatdavletov.financeconsultante.shared.domain.Money
import java.time.LocalDate

@Suppress("TooManyFunctions") // One bounded plain-text presenter owns the analytics wording and sanitization.
object AnalyticsMessages {
    fun report(report: AnalyticsReport): String {
        val prefix = prefix(report)
        val suffix = suffix(report)
        val categories = categoryLines(report)
        val result = StringBuilder(prefix)
        var included = 0
        categories.forEach { line ->
            if (result.length + line.length + suffix.length + OMITTED_RESERVE <= MAX_TEXT_LENGTH) {
                result.append(line)
                included++
            }
        }
        val omitted = categories.size - included
        if (omitted > 0) result.append("… ещё $omitted категорий не показано из-за ограничения Telegram.\n")
        result.append(suffix)
        check(result.length <= MAX_TEXT_LENGTH) { "Analytics report exceeds Telegram limit" }
        return result.toString()
    }

    fun skipped(
        period: AnalyticsPeriod,
        periodStart: LocalDate,
    ): String =
        "${periodTitle(period)} отчёт за период с $periodStart пропущен: 48-часовое окно восстановления " +
            "завершилось. Актуальную аналитику можно запросить командой /report ${periodCommand(period)}."

    private fun prefix(report: AnalyticsReport): String {
        val range = report.ranges.current
        val kind = if (report.completedPeriod) "Завершённый" else "Текущий"
        val title = if (report.period == AnalyticsPeriod.WEEK) "недельный" else "месячный"
        val asOf = report.asOf.atZone(ACCOUNTING_ZONE).toLocalDateTime()
        return buildString {
            append("$kind $title отчёт: ${range.startInclusive} — ${range.endExclusive.minusDays(1)}")
            append(" (срез $asOf Asia/Almaty).\n")
            if (report.current.empty) {
                append("За период проведённых операций нет.\n")
            } else {
                append("Доходы: ${money(report.current.income)}. ")
                append("Расходы: ${money(report.current.expenses)}. ")
                append("Возвраты: ${money(report.current.refunds)}.\n")
                append("Чистые расходы: ${money(report.current.netExpenses)}.\n")
            }
            append("Категории (чистые расходы; расходы минус возвраты):\n")
            if (report.current.categories.isEmpty()) append("— нет расходов и возвратов.\n")
        }
    }

    private fun suffix(report: AnalyticsReport): String =
        buildString {
            if (!report.currentComplete) {
                val started = report.accountingStartedAt.atZone(ACCOUNTING_ZONE).toLocalDate()
                append("Ограничение: учёт начат $started, поэтому текущий период неполный.\n")
            }
            append(comparison(report))
            append("\n")
            append(recommendation(report))
            append("\nНачальный остаток, черновики, ожидаемые доходы и планы не включены в доходы; ")
            append("VOIDED-операции исключены.")
        }

    private fun categoryLines(report: AnalyticsReport): List<String> {
        val currentById = report.current.categories.associateBy(CategoryAnalytics::categoryId)
        val previousById = report.previous.categories.associateBy(CategoryAnalytics::categoryId)
        return (currentById.keys + previousById.keys)
            .map { id ->
                val current = currentById[id]
                val previous = previousById[id]
                val name = sanitize(current?.name ?: checkNotNull(previous).name)
                val change = report.categoryChange(id)
                val detail = if (report.comparisonComplete) "; ${changeText(change, compact = true)}" else ""
                Triple(change.current.amount.abs(), name, "— $name: ${money(change.current)}$detail.\n")
            }.sortedWith(
                compareByDescending<Triple<java.math.BigDecimal, String, String>> { it.first }
                    .thenBy { it.second },
            ).map { it.third }
    }

    private fun comparison(report: AnalyticsReport): String {
        val previousRange = report.ranges.previous
        val label = "${previousRange.startInclusive} — ${previousRange.endExclusive.minusDays(1)}"
        if (!report.comparisonComplete) {
            return "Сравнение с $label ограничено: учёт предыдущего периода неполный; проценты не рассчитываются."
        }
        return "Сравнение с $label: чистые расходы ${changeText(report.netExpenseChange(), compact = false)}."
    }

    private fun changeText(
        change: AnalyticsChange,
        compact: Boolean,
    ): String {
        val previous = if (compact) "было ${money(change.previous)}" else "были ${money(change.previous)}"
        val difference = signedMoney(change.difference)
        val percent = change.percentage?.let { ", ${signed(it.toPlainString())}%" }
        return if (percent == null) {
            "$previous; изменение $difference; процент не рассчитан при нулевой или отрицательной базе"
        } else {
            "$previous; изменение $difference$percent"
        }
    }

    private fun recommendation(report: AnalyticsReport): String {
        val change = report.netExpenseChange()
        val largest = report.current.categories.maxByOrNull { it.netExpenses.amount }
        return when {
            report.current.empty -> {
                "Рекомендация: добавляйте фактические доходы и расходы — без них динамику оценить нельзя."
            }

            report.current.netExpenses.amount > report.current.income.amount -> {
                "Рекомендация: чистые расходы выше учтённых доходов за период; проверьте необязательные траты."
            }

            report.comparisonComplete && change.difference.amount.signum() > 0 -> {
                "Рекомендация: чистые расходы выросли; начните с крупнейшей категории периода."
            }

            largest != null && largest.netExpenses.amount.signum() > 0 -> {
                "Рекомендация: крупнейшая категория — ${sanitize(largest.name)} (${money(largest.netExpenses)}); " +
                    "проверьте её перед следующим периодом."
            }

            else -> {
                "Рекомендация: рост чистых расходов не выявлен; продолжайте фиксировать операции."
            }
        }
    }

    private fun sanitize(value: String): String =
        value
            .map { if (it.isISOControl() || it == '\u2028' || it == '\u2029') ' ' else it }
            .joinToString("")
            .replace(WHITESPACE, " ")
            .trim()
            .ifBlank { "Без названия" }

    private fun signedMoney(value: Money): String = (if (value.amount.signum() > 0) "+" else "") + money(value)

    private fun signed(value: String): String = if (value.startsWith("-")) value else "+$value"

    private fun money(value: Money): String = "${value.amount.toPlainString()} ${value.currency}"

    private fun periodTitle(period: AnalyticsPeriod): String =
        if (period == AnalyticsPeriod.WEEK) "Недельный" else "Месячный"

    private fun periodCommand(period: AnalyticsPeriod): String = if (period == AnalyticsPeriod.WEEK) "week" else "month"

    private val WHITESPACE = Regex("\\s+")
    private const val MAX_TEXT_LENGTH = 4096
    private const val OMITTED_RESERVE = 96
}
