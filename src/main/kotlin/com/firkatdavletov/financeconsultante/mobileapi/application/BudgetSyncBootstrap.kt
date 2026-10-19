package com.firkatdavletov.financeconsultante.mobileapi.application

import com.firkatdavletov.financeconsultante.access.application.FamilyBudgetAccess
import com.firkatdavletov.financeconsultante.ledger.application.LedgerInteractionRepository
import com.firkatdavletov.financeconsultante.ledger.domain.TransactionCategory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** The offline deletion projection needs the same stable fallback category as the server. */
@Service
class BudgetSyncBootstrap(
    private val access: FamilyBudgetAccess,
    private val ledger: LedgerInteractionRepository,
) {
    @Transactional
    fun prepare(user: Long) {
        val member = access.activeMember(user) ?: throw MobileApiAccessDenied()
        ledger.resolveCategory(member.householdId, TransactionCategory.OTHER)
    }
}
