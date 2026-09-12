package com.firkatdavletov.financeconsultante.analytics.domain

import com.firkatdavletov.financeconsultante.analytics.application.AnalyticsMessages
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class AnalyticsReportTests {
    @Test
    fun `current month compares the same available days without crossing a shorter previous month`() {
        val marchFirst = ReportRanges.current(AnalyticsPeriod.MONTH, LocalDate.parse("2026-03-01"))
        val marchEnd = ReportRanges.current(AnalyticsPeriod.MONTH, LocalDate.parse("2026-03-31"))

        assertThat(marchFirst.current).isEqualTo(range("2026-03-01", "2026-03-02"))
        assertThat(marchFirst.previous).isEqualTo(range("2026-02-01", "2026-02-02"))
        assertThat(marchEnd.current).isEqualTo(range("2026-03-01", "2026-04-01"))
        assertThat(marchEnd.previous).isEqualTo(range("2026-02-01", "2026-03-01"))
    }

    @Test
    fun `completed week uses Monday boundary and a full previous week`() {
        val ranges = ReportRanges.completed(AnalyticsPeriod.WEEK, LocalDate.parse("2026-08-31"))

        assertThat(ranges.current).isEqualTo(range("2026-08-31", "2026-09-07"))
        assertThat(ranges.previous).isEqualTo(range("2026-08-24", "2026-08-31"))
    }

    @Test
    fun `zero comparison base never creates a growth percentage`() {
        val report = report(current = period("100", "0"), previous = PeriodAnalytics.empty())

        assertThat(report.netExpenseChange().percentage).isNull()
        assertThat(AnalyticsMessages.report(report))
            .contains("процент не рассчитан при нулевой или отрицательной базе")
            .doesNotContain("Infinity", "NaN", "% рост")
    }

    @Test
    fun `empty history produces an actionable report`() {
        val text = AnalyticsMessages.report(report(PeriodAnalytics.empty(), PeriodAnalytics.empty()))

        assertThat(text).contains(
            "За период проведённых операций нет",
            "без них динамику оценить нельзя",
            "процент не рассчитан",
        )
    }

    @Test
    fun `message sanitizes category line breaks and remains within Telegram limit`() {
        val categories =
            (1..100).map { index ->
                CategoryAnalytics(
                    UUID.randomUUID(),
                    "Категория\n$index ${"д".repeat(70)}",
                    Money.of("1"),
                    Money.of("0"),
                )
            }
        val report =
            report(
                current = PeriodAnalytics(Money.of("0"), Money.of("100"), Money.of("0"), categories),
                previous = PeriodAnalytics.empty(),
            )

        val text = AnalyticsMessages.report(report)

        assertThat(text.length).isLessThanOrEqualTo(4096)
        assertThat(text).contains("Категория 1").contains("не показано из-за ограничения Telegram")
        assertThat(text).doesNotContain("Категория\n")
        assertThat(text).endsWith("VOIDED-операции исключены.")
    }

    @Test
    fun `partial first period explains why comparison percentages are unavailable`() {
        val report =
            report(
                current = period("20", "0"),
                previous = period("10", "0"),
                currentComplete = false,
                comparisonComplete = false,
                accountingStartedAt = Instant.parse("2026-09-03T00:00:00Z"),
            )

        assertThat(AnalyticsMessages.report(report)).contains(
            "текущий период неполный",
            "учёт предыдущего периода неполный; проценты не рассчитываются",
        )
    }

    private fun report(
        current: PeriodAnalytics,
        previous: PeriodAnalytics,
        currentComplete: Boolean = true,
        comparisonComplete: Boolean = true,
        accountingStartedAt: Instant = Instant.parse("2026-01-01T00:00:00Z"),
    ): AnalyticsReport =
        AnalyticsReport(
            AnalyticsPeriod.WEEK,
            ReportRanges.completed(AnalyticsPeriod.WEEK, LocalDate.parse("2026-08-31")),
            current,
            previous,
            Instant.parse("2026-09-07T02:00:00Z"),
            accountingStartedAt,
            completedPeriod = true,
            currentComplete,
            comparisonComplete,
        )

    private fun period(
        expenses: String,
        refunds: String,
    ): PeriodAnalytics {
        val category =
            CategoryAnalytics(UUID.randomUUID(), "Продукты", Money.of(expenses), Money.of(refunds))
        return PeriodAnalytics(Money.of("0"), Money.of(expenses), Money.of(refunds), listOf(category))
    }

    private fun range(
        start: String,
        end: String,
    ): ReportRange = ReportRange(LocalDate.parse(start), LocalDate.parse(end))
}
