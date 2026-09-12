package com.firkatdavletov.financeconsultante.analytics.application

import com.firkatdavletov.financeconsultante.analytics.domain.AnalyticsPeriod
import com.firkatdavletov.financeconsultante.budget.application.DailyReportJob
import com.firkatdavletov.financeconsultante.budget.application.DailyReportJobRepository
import com.firkatdavletov.financeconsultante.budget.application.DailyReportStatus
import com.firkatdavletov.financeconsultante.budget.application.ReportType
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.telegram.domain.ACCOUNTING_ZONE
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class AnalyticsReportCoordinator(
    private val jobs: DailyReportJobRepository,
    private val reports: AnalyticsReportService,
    private val clock: Clock,
) {
    @Transactional
    fun prepare(
        botId: Long,
        ownerId: Long,
    ) {
        require(botId > 0 && ownerId > 0)
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val today = now.atZone(ACCOUNTING_ZONE).toLocalDate()
        jobs.recipients(ownerId).forEach { recipient ->
            AnalyticsPeriod.entries.forEach { period ->
                val dueDate = dueDate(period, today)
                val scheduled = ZonedDateTime.of(dueDate, recipient.reportTime, ACCOUNTING_ZONE).toInstant()
                val catchUp = scheduled.plus(CATCH_UP)
                if (dueDate == today || now in scheduled..catchUp) {
                    jobs.schedule(
                        DailyReportJob(
                            UUID.randomUUID(),
                            OwnedLedger(
                                recipient.householdId,
                                recipient.memberId,
                                recipient.ownerId,
                                recipient.accountingStartedAt,
                            ),
                            botId,
                            period.previousStart(dueDate),
                            scheduled,
                            catchUp,
                            DailyReportStatus.SCHEDULED,
                            0,
                            reportType = period.reportType(),
                        ),
                        now,
                    )
                }
            }
        }
        jobs.markPeriodicExpired(botId, now).forEach { expired ->
            val period = expired.reportType.analyticsPeriod()
            jobs.queueSkipped(expired, AnalyticsMessages.skipped(period, expired.date), now)
        }
    }

    @Transactional
    fun claim(
        botId: Long,
        leaseDuration: Duration,
    ): DailyReportJob? = jobs.claimPeriodic(botId, clock.instant().truncatedTo(ChronoUnit.MICROS), leaseDuration)

    @Transactional
    fun complete(job: DailyReportJob) {
        val current = jobs.lock(job) ?: return
        val period = current.reportType.analyticsPeriod()
        val report = reports.completed(current.owner, period, current.date)
        jobs.queue(current, AnalyticsMessages.report(report), clock.instant().truncatedTo(ChronoUnit.MICROS))
    }

    @Transactional
    fun retry(
        job: DailyReportJob,
        delay: Duration,
    ) {
        jobs.retry(job, clock.instant().truncatedTo(ChronoUnit.MICROS), delay)
    }

    private fun dueDate(
        period: AnalyticsPeriod,
        today: LocalDate,
    ): LocalDate = period.startOf(today)

    private fun AnalyticsPeriod.reportType(): ReportType =
        when (this) {
            AnalyticsPeriod.WEEK -> ReportType.WEEKLY
            AnalyticsPeriod.MONTH -> ReportType.MONTHLY
        }

    private fun ReportType.analyticsPeriod(): AnalyticsPeriod =
        when (this) {
            ReportType.WEEKLY -> AnalyticsPeriod.WEEK
            ReportType.MONTHLY -> AnalyticsPeriod.MONTH
            ReportType.DAILY -> error("Daily job is not an analytics report")
        }

    companion object {
        private val CATCH_UP: Duration = Duration.ofHours(48)
    }
}

class AnalyticsReportWorker(
    private val botId: Long,
    private val ownerId: Long,
    private val coordinator: AnalyticsReportCoordinator,
    private val leaseDuration: Duration,
    private val retryDelay: Duration,
) {
    @Suppress("TooGenericExceptionCaught") // Every processing failure must release the durable lease for retry.
    fun runOnce(): Boolean {
        coordinator.prepare(botId, ownerId)
        val job = coordinator.claim(botId, leaseDuration) ?: return false
        try {
            coordinator.complete(job)
        } catch (failure: RuntimeException) {
            coordinator.retry(job, retryDelay)
            throw failure
        }
        return true
    }
}
