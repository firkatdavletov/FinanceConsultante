package com.firkatdavletov.financeconsultante.budget.application

import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.telegram.domain.ACCOUNTING_ZONE
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class DailyReportCoordinator(
    private val jobs: DailyReportJobRepository,
    private val reports: BudgetReportService,
    private val clock: Clock,
) {
    @Transactional
    fun prepare(
        botId: Long,
        ownerId: Long,
    ) {
        require(botId > 0 && ownerId > 0)
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val date = now.atZone(ACCOUNTING_ZONE).toLocalDate()
        jobs.recipients(ownerId).forEach { recipient ->
            val scheduled = ZonedDateTime.of(date, recipient.reportTime, ACCOUNTING_ZONE).toInstant()
            val noon = ZonedDateTime.of(date, LocalTime.NOON, ACCOUNTING_ZONE).toInstant()
            val endOfDay =
                date
                    .plusDays(1)
                    .atStartOfDay(ACCOUNTING_ZONE)
                    .toInstant()
                    .minusMillis(1)
            val catchUp = if (scheduled <= noon) noon else endOfDay
            jobs.schedule(
                DailyReportJob(
                    UUID.randomUUID(),
                    OwnedLedger(
                        recipient.householdId,
                        recipient.memberId,
                        recipient.ownerId,
                        recipient.accountingStartedAt,
                        recipient.role,
                    ),
                    botId,
                    date,
                    scheduled,
                    catchUp,
                    DailyReportStatus.SCHEDULED,
                    0,
                ),
                now,
            )
        }
        jobs.markExpired(botId, now).forEach { expired ->
            jobs.queueSkipped(expired, BudgetMessages.skipped(expired.date), now)
        }
    }

    @Transactional
    fun claim(
        botId: Long,
        leaseDuration: Duration,
    ): DailyReportJob? = jobs.claim(botId, clock.instant().truncatedTo(ChronoUnit.MICROS), leaseDuration)

    @Transactional
    fun complete(job: DailyReportJob) {
        val current = jobs.lock(job) ?: return
        val view = reports.today(current.owner, replaceSnapshot = true)
        jobs.queue(current, BudgetMessages.daily(view, morning = true), clock.instant().truncatedTo(ChronoUnit.MICROS))
    }

    @Transactional
    fun retry(
        job: DailyReportJob,
        delay: Duration,
    ) {
        jobs.retry(job, clock.instant().truncatedTo(ChronoUnit.MICROS), delay)
    }
}

class DailyReportWorker(
    private val botId: Long,
    private val ownerId: Long,
    private val coordinator: DailyReportCoordinator,
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
