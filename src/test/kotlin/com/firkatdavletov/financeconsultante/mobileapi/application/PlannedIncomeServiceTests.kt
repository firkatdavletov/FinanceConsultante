package com.firkatdavletov.financeconsultante.mobileapi.application

import com.firkatdavletov.financeconsultante.access.application.FamilyBudgetAccess
import com.firkatdavletov.financeconsultante.access.domain.FamilyInvitation
import com.firkatdavletov.financeconsultante.access.domain.FamilyMember
import com.firkatdavletov.financeconsultante.access.domain.MemberRole
import com.firkatdavletov.financeconsultante.ledger.application.HistoryItem
import com.firkatdavletov.financeconsultante.ledger.application.LedgerInteractionRepository
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.ledger.domain.TransactionCategory
import com.firkatdavletov.financeconsultante.recurring.application.RecurringPlanService
import com.firkatdavletov.financeconsultante.recurring.domain.ExpectedIncome
import com.firkatdavletov.financeconsultante.recurring.domain.ExpectedIncomeView
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class PlannedIncomeServiceTests {
    @Test
    fun `reads open incomes for the active member household using the local API date`() {
        val householdId = UUID.randomUUID()
        val member = member(householdId)
        val owner = OwnedLedger(householdId, member.id, member.telegramUserId, ACCOUNTING_START, member.role)
        val income = expectedIncome(householdId, member.id)
        val recurring = Mockito.mock(RecurringPlanService::class.java)
        Mockito.`when`(recurring.plannedIncomes(owner)).thenReturn(listOf(income))
        val service =
            PlannedIncomeService(
                FakeAccess(member),
                FakeLedgerRepository(owner),
                recurring,
                Clock.fixed(API_INSTANT, ZoneOffset.UTC),
            )

        val result = service.plannedIncomes(member.telegramUserId)

        assertThat(result.asOf).isEqualTo(AS_OF)
        assertThat(result.items).containsExactly(income)
    }

    @Test
    fun `rejects inactive or mismatched membership before reading incomes`() {
        val householdId = UUID.randomUUID()
        val member = member(householdId)
        val recurring = Mockito.mock(RecurringPlanService::class.java)
        val owner = OwnedLedger(UUID.randomUUID(), member.id, member.telegramUserId, ACCOUNTING_START, member.role)

        assertThatThrownBy {
            PlannedIncomeService(
                FakeAccess(null),
                FakeLedgerRepository(owner),
                recurring,
                Clock.fixed(API_INSTANT, ZoneOffset.UTC),
            ).plannedIncomes(member.telegramUserId)
        }.isInstanceOf(MobileApiAccessDenied::class.java)

        assertThatThrownBy {
            PlannedIncomeService(
                FakeAccess(member),
                FakeLedgerRepository(owner),
                recurring,
                Clock.fixed(API_INSTANT, ZoneOffset.UTC),
            ).plannedIncomes(member.telegramUserId)
        }.isInstanceOf(MobileApiAccessDenied::class.java)
        Mockito.verifyNoInteractions(recurring)
    }

    private fun member(householdId: UUID) =
        FamilyMember(
            UUID.randomUUID(),
            householdId,
            TELEGRAM_USER_ID,
            MemberRole.MEMBER,
            reportsEnabled = false,
            joinedAt = ACCOUNTING_START,
        )

    private fun expectedIncome(
        householdId: UUID,
        memberId: UUID,
    ): ExpectedIncomeView {
        val createdAt = Instant.parse("2026-10-01T00:00:00Z")
        return ExpectedIncomeView(
            ExpectedIncome(
                UUID.randomUUID(),
                householdId,
                memberId,
                UUID.randomUUID(),
                Money.of("100"),
                AS_OF,
                createdAt = createdAt,
            ),
            "Зарплата",
            Money.of("0"),
            postedMatches = 0,
        )
    }

    private class FakeAccess(
        private val member: FamilyMember?,
    ) : FamilyBudgetAccess {
        override fun canReceive(telegramUserId: Long) = false

        override fun canProcess(
            telegramUserId: Long,
            memberId: UUID?,
        ) = false

        override fun canDeliver(
            telegramUserId: Long,
            memberId: UUID,
            report: Boolean,
        ) = false

        override fun activeMember(
            telegramUserId: Long,
            memberId: UUID?,
        ) = member

        override fun pendingInvitation(telegramUserId: Long): FamilyInvitation? = null
    }

    private class FakeLedgerRepository(
        private val owner: OwnedLedger?,
    ) : LedgerInteractionRepository {
        override fun owned(ownerId: Long): OwnedLedger? = owner

        override fun resolveCategory(
            householdId: UUID,
            key: TransactionCategory,
        ): Category = error("Not used")

        override fun history(
            householdId: UUID,
            before: Long,
            limit: Int,
        ): List<HistoryItem> = error("Not used")

        override fun purchaseHistory(
            householdId: UUID,
            before: Long,
            limit: Int,
        ): List<HistoryItem> = error("Not used")
    }

    companion object {
        private const val TELEGRAM_USER_ID = 991002L
        private val ACCOUNTING_START = Instant.parse("2026-09-01T00:00:00Z")
        private val API_INSTANT = Instant.parse("2026-10-06T20:00:00Z")
        private val AS_OF = LocalDate.parse("2026-10-07")
    }
}
