package com.firkatdavletov.financeconsultante.analytics.application

import com.firkatdavletov.financeconsultante.analytics.domain.AnalyticsPeriod
import com.firkatdavletov.financeconsultante.analytics.domain.AnalyticsReport
import com.firkatdavletov.financeconsultante.analytics.domain.PeriodAnalytics
import com.firkatdavletov.financeconsultante.analytics.domain.ReportRange
import com.firkatdavletov.financeconsultante.analytics.domain.ReportRanges
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.shared.domain.ACCOUNTING_ZONE
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

interface AnalyticsReportRepository {
    fun owns(owner: OwnedLedger): Boolean

    fun aggregate(
        owner: OwnedLedger,
        range: ReportRange,
    ): PeriodAnalytics
}

@Service
@Transactional(
    readOnly = true,
    isolation = Isolation.REPEATABLE_READ,
    propagation = Propagation.REQUIRES_NEW,
)
class AnalyticsReportService(
    private val repository: AnalyticsReportRepository,
    private val clock: Clock,
) {
    fun current(
        owner: OwnedLedger,
        period: AnalyticsPeriod,
    ): AnalyticsReport {
        val asOf = now()
        val ranges = ReportRanges.current(period, asOf.atZone(ACCOUNTING_ZONE).toLocalDate())
        return report(owner, period, ranges, asOf, completed = false)
    }

    fun completed(
        owner: OwnedLedger,
        period: AnalyticsPeriod,
        periodStart: LocalDate,
    ): AnalyticsReport {
        val asOf = now()
        val ranges = ReportRanges.completed(period, periodStart)
        require(ranges.current.endExclusive <= asOf.atZone(ACCOUNTING_ZONE).toLocalDate()) {
            "Only a completed report period can be generated"
        }
        return report(owner, period, ranges, asOf, completed = true)
    }

    private fun report(
        owner: OwnedLedger,
        period: AnalyticsPeriod,
        ranges: ReportRanges,
        asOf: Instant,
        completed: Boolean,
    ): AnalyticsReport {
        require(repository.owns(owner)) { "Budget access denied" }
        val accountingStart = owner.startedAt
        val currentComplete = accountingStart <= startInstant(ranges.current.startInclusive)
        val comparisonComplete = accountingStart <= startInstant(ranges.previous.startInclusive)
        return AnalyticsReport(
            period,
            ranges,
            repository.aggregate(owner, ranges.current),
            repository.aggregate(owner, ranges.previous),
            asOf,
            accountingStart,
            completed,
            currentComplete,
            comparisonComplete,
        )
    }

    private fun startInstant(date: LocalDate): Instant = date.atStartOfDay(ACCOUNTING_ZONE).toInstant()

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)
}
