package com.firkatdavletov.financeconsultante.budget.application

import com.firkatdavletov.financeconsultante.access.domain.MemberRole
import com.firkatdavletov.financeconsultante.budget.domain.DailyBudgetSnapshot
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.shared.domain.Money
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

data class BudgetSettingsView(
    val protectedReserve: Money,
    val version: Long,
)

data class DailyReportRecipient(
    val householdId: UUID,
    val memberId: UUID,
    val ownerId: Long,
    val reportTime: LocalTime,
    val accountingStartedAt: Instant,
    val role: MemberRole = MemberRole.OWNER,
)

enum class DailyReportStatus {
    SCHEDULED,
    PROCESSING,
    QUEUED,
    SENT,
    SKIPPED,
    FAILED,
}

enum class ReportType {
    DAILY,
    WEEKLY,
    MONTHLY,
}

data class DailyReportJob(
    val id: UUID,
    val owner: OwnedLedger,
    val botId: Long,
    val date: LocalDate,
    val scheduledFor: Instant,
    val catchUpUntil: Instant,
    val status: DailyReportStatus,
    val attempts: Int,
    val leaseToken: UUID? = null,
    val reportType: ReportType = ReportType.DAILY,
)

interface BudgetReportRepository {
    fun settings(
        owner: OwnedLedger,
        lock: Boolean = false,
    ): BudgetSettingsView

    fun ledgerRevision(householdId: UUID): Long

    fun snapshot(
        householdId: UUID,
        date: LocalDate,
    ): DailyBudgetSnapshot?

    fun save(snapshot: DailyBudgetSnapshot)

    fun ordinarySpendingAfter(snapshot: DailyBudgetSnapshot): Money

    fun hasInvalidatingLedgerChanges(snapshot: DailyBudgetSnapshot): Boolean
}

interface DailyReportJobRepository {
    fun recipients(ownerId: Long): List<DailyReportRecipient>

    fun schedule(
        job: DailyReportJob,
        now: Instant,
    ): Boolean

    fun markExpired(
        botId: Long,
        now: Instant,
    ): List<DailyReportJob>

    fun markPeriodicExpired(
        botId: Long,
        now: Instant,
    ): List<DailyReportJob>

    fun claim(
        botId: Long,
        now: Instant,
        leaseDuration: Duration,
    ): DailyReportJob?

    fun claimPeriodic(
        botId: Long,
        now: Instant,
        leaseDuration: Duration,
    ): DailyReportJob?

    fun lock(job: DailyReportJob): DailyReportJob?

    fun queue(
        job: DailyReportJob,
        text: String,
        now: Instant,
    )

    fun queueSkipped(
        job: DailyReportJob,
        text: String,
        now: Instant,
    )

    fun retry(
        job: DailyReportJob,
        now: Instant,
        delay: Duration,
    )
}
