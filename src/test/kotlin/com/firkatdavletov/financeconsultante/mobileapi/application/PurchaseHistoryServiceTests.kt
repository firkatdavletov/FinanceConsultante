package com.firkatdavletov.financeconsultante.mobileapi.application

import com.firkatdavletov.financeconsultante.access.application.FamilyBudgetAccess
import com.firkatdavletov.financeconsultante.access.domain.FamilyInvitation
import com.firkatdavletov.financeconsultante.access.domain.FamilyMember
import com.firkatdavletov.financeconsultante.access.domain.MemberRole
import com.firkatdavletov.financeconsultante.ledger.application.HistoryItem
import com.firkatdavletov.financeconsultante.ledger.application.LedgerInteractionRepository
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.ledger.domain.LedgerEntry
import com.firkatdavletov.financeconsultante.ledger.domain.TransactionCategory
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PurchaseHistoryServiceTests {
    @Test
    fun `uses active household and returns exclusive next cursor`() {
        val householdId = UUID.randomUUID()
        val member = member(householdId)
        val repository = FakeLedgerRepository(householdId, listOf(item(householdId, 9), item(householdId, 7)))
        val service = PurchaseHistoryService(FakeAccess(member), repository)

        val page = service.history(member.telegramUserId, null, 1)

        assertThat(page.items.map(HistoryItem::sequence)).containsExactly(9)
        assertThat(page.nextCursor).isEqualTo(9)
        assertThat(repository.requestedBefore).isEqualTo(Long.MAX_VALUE)
        assertThat(repository.requestedLimit).isEqualTo(2)
    }

    @Test
    fun `rejects invalid pagination and inactive membership`() {
        val householdId = UUID.randomUUID()
        val member = member(householdId)
        val repository = FakeLedgerRepository(householdId, emptyList())
        val service = PurchaseHistoryService(FakeAccess(member), repository)
        assertThatThrownBy { service.history(member.telegramUserId, 0, 20) }
            .isInstanceOf(InvalidPurchaseHistoryRequest::class.java)
        assertThatThrownBy { service.history(member.telegramUserId, null, 51) }
            .isInstanceOf(InvalidPurchaseHistoryRequest::class.java)
        assertThatThrownBy {
            PurchaseHistoryService(FakeAccess(null), repository).history(member.telegramUserId, null, 20)
        }.isInstanceOf(MobileApiAccessDenied::class.java)
        assertThat(repository.calls).isZero()
    }

    private fun member(householdId: UUID) =
        FamilyMember(
            UUID.randomUUID(),
            householdId,
            991002,
            MemberRole.MEMBER,
            reportsEnabled = false,
            joinedAt = CREATED_AT,
        )

    private fun item(
        householdId: UUID,
        sequence: Long,
    ): HistoryItem {
        val categoryId = UUID.randomUUID()
        return HistoryItem(
            sequence,
            "Покупки",
            LedgerEntry(
                UUID.randomUUID(),
                householdId,
                UUID.randomUUID(),
                categoryId,
                UUID.randomUUID(),
                Direction.EXPENSE,
                Money.of("10"),
                Money.of("10"),
                ACCOUNTING_START,
                OCCURRED_AT,
                CREATED_AT,
                "mobile:test:$sequence",
            ),
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
        private val householdId: UUID,
        private val rows: List<HistoryItem>,
    ) : LedgerInteractionRepository {
        var calls = 0
        var requestedBefore: Long? = null
        var requestedLimit: Int? = null

        override fun owned(ownerId: Long): OwnedLedger? = null

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
        ): List<HistoryItem> {
            check(householdId == this.householdId)
            calls++
            requestedBefore = before
            requestedLimit = limit
            return rows.filter { it.sequence < before }.take(limit)
        }
    }

    companion object {
        private val ACCOUNTING_START = Instant.parse("2026-09-01T00:00:00Z")
        private val OCCURRED_AT = Instant.parse("2026-09-02T00:00:00Z")
        private val CREATED_AT = Instant.parse("2026-09-03T00:00:00Z")
    }
}
