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
import com.firkatdavletov.financeconsultante.recurring.application.PlannedExpenseSchedule
import com.firkatdavletov.financeconsultante.recurring.application.RecurringPlanService
import com.firkatdavletov.financeconsultante.recurring.domain.RecurringOccurrence
import com.firkatdavletov.financeconsultante.recurring.domain.RecurringOccurrenceView
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

class PlannedExpenseServiceTests {
    @Test
    fun `reads the active member household using the local API date`() {
        val householdId = UUID.randomUUID()
        val member = member(householdId)
        val owner = OwnedLedger(householdId, member.id, member.telegramUserId, ACCOUNTING_START, member.role)
        val expense = occurrence(householdId)
        val recurring = Mockito.mock(RecurringPlanService::class.java)
        Mockito
            .`when`(recurring.plannedExpenses(owner, AS_OF))
            .thenReturn(PlannedExpenseSchedule(AS_OF, THROUGH, listOf(expense)))
        val service =
            PlannedExpenseService(
                FakeAccess(member),
                FakeLedgerRepository(owner),
                recurring,
                Clock.fixed(API_INSTANT, ZoneOffset.UTC),
            )

        val result = service.plannedExpenses(member.telegramUserId)

        assertThat(result.asOf).isEqualTo(AS_OF)
        assertThat(result.throughInclusive).isEqualTo(THROUGH)
        assertThat(result.items).containsExactly(expense)
    }

    @Test
    fun `rejects inactive or mismatched membership before reading plans`() {
        val householdId = UUID.randomUUID()
        val member = member(householdId)
        val recurring = Mockito.mock(RecurringPlanService::class.java)
        val owner = OwnedLedger(UUID.randomUUID(), member.id, member.telegramUserId, ACCOUNTING_START, member.role)

        assertThatThrownBy {
            PlannedExpenseService(
                FakeAccess(null),
                FakeLedgerRepository(owner),
                recurring,
                Clock.fixed(API_INSTANT, ZoneOffset.UTC),
            ).plannedExpenses(member.telegramUserId)
        }.isInstanceOf(MobileApiAccessDenied::class.java)

        assertThatThrownBy {
            PlannedExpenseService(
                FakeAccess(member),
                FakeLedgerRepository(owner),
                recurring,
                Clock.fixed(API_INSTANT, ZoneOffset.UTC),
            ).plannedExpenses(member.telegramUserId)
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

    private fun occurrence(householdId: UUID): RecurringOccurrenceView {
        val createdAt = Instant.parse("2026-10-01T00:00:00Z")
        return RecurringOccurrenceView(
            RecurringOccurrence(
                UUID.randomUUID(),
                householdId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                Money.of("100"),
                AS_OF,
                createdAt = createdAt,
            ),
            "Жильё",
            Money.of("40"),
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
        private val THROUGH = LocalDate.parse("2026-12-31")
    }
}
