package com.firkatdavletov.financeconsultante.mobileapi.application

import com.firkatdavletov.financeconsultante.access.application.FamilyBudgetAccess
import com.firkatdavletov.financeconsultante.ledger.application.LedgerInteractionRepository
import com.firkatdavletov.financeconsultante.ledger.domain.TransactionCategory
import com.firkatdavletov.financeconsultante.recurring.application.RecurringPlanService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** The offline deletion projection needs the same stable fallback category as the server. */
@Service
class BudgetSyncBootstrap(
    private val access: FamilyBudgetAccess,
    private val ledger: LedgerInteractionRepository,
    private val recurring: RecurringPlanService,
    private val clock: Clock,
) {
    @Transactional
    fun prepare(
        user: Long,
        materialize: Boolean = false,
    ) {
        val member = access.activeMember(user) ?: throw MobileApiAccessDenied()
        ledger.resolveCategory(member.householdId, TransactionCategory.OTHER)
        if (materialize) {
            val owner = ledger.owned(user) ?: throw MobileApiAccessDenied()
            val today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Almaty")))
            recurring.ensureOccurrences(owner, YearMonth.from(today.plusMonths(2)).atEndOfMonth())
        }
    }
}
