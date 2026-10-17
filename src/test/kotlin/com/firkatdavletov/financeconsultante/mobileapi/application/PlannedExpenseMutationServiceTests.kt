package com.firkatdavletov.financeconsultante.mobileapi.application

import com.firkatdavletov.financeconsultante.access.application.FamilyBudgetAccess
import com.firkatdavletov.financeconsultante.access.domain.FamilyInvitation
import com.firkatdavletov.financeconsultante.access.domain.FamilyMember
import com.firkatdavletov.financeconsultante.access.domain.MemberRole
import com.firkatdavletov.financeconsultante.ledger.application.HistoryItem
import com.firkatdavletov.financeconsultante.ledger.application.LedgerInteractionRepository
import com.firkatdavletov.financeconsultante.ledger.application.OperationHistoryFilter
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.ledger.domain.TransactionCategory
import com.firkatdavletov.financeconsultante.recurring.application.PlanChange
import com.firkatdavletov.financeconsultante.recurring.application.RecurringPlanService
import com.firkatdavletov.financeconsultante.recurring.application.RecurringRuleView
import com.firkatdavletov.financeconsultante.recurring.domain.OccurrenceStatus
import com.firkatdavletov.financeconsultante.recurring.domain.RecurrenceFrequency
import com.firkatdavletov.financeconsultante.recurring.domain.RecurringOccurrence
import com.firkatdavletov.financeconsultante.recurring.domain.RecurringOccurrenceView
import com.firkatdavletov.financeconsultante.recurring.domain.RecurringRule
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

class PlannedExpenseMutationServiceTests {
    @Test
    fun `owner creates updates and deletes a recurring expense rule`() {
        val fixture = fixture(MemberRole.OWNER)
        val created = ruleView(fixture.owner, version = 0, amount = "100")
        val updated = ruleView(fixture.owner, id = created.rule.id, version = 1, amount = "125.50")
        Mockito
            .`when`(
                fixture.recurring.createRule(
                    fixture.owner,
                    RecurrenceFrequency.MONTHLY,
                    TODAY.dayOfMonth,
                    Money.of("100"),
                    CATEGORY_ID,
                    TODAY,
                ),
            ).thenReturn(PlanChange.Applied(created))
        Mockito.`when`(fixture.recurring.rule(fixture.owner, created.rule.id)).thenReturn(created.rule, updated.rule)
        Mockito
            .`when`(
                fixture.recurring.updateRuleAmount(
                    fixture.owner,
                    created.rule.id,
                    0,
                    Money.of("125.50"),
                    TODAY,
                ),
            ).thenReturn(PlanChange.Applied(updated))
        Mockito
            .`when`(fixture.recurring.deactivateRule(fixture.owner, created.rule.id, 1, TODAY))
            .thenReturn(PlanChange.Applied(updated.copy(rule = updated.rule.copy(active = false, version = 2))))

        val createResult =
            fixture.service.create(
                API_USER_ID,
                CATEGORY_ID,
                "100",
                "MONTHLY",
                TODAY.dayOfMonth,
                TODAY,
            )
        val updateResult = fixture.service.update(API_USER_ID, created.rule.id, 0, "125.50")
        fixture.service.delete(API_USER_ID, created.rule.id, 1)

        assertThat(createResult).isEqualTo(created)
        assertThat(updateResult).isEqualTo(updated)
        Mockito.verify(fixture.recurring).deactivateRule(fixture.owner, created.rule.id, 1, TODAY)
    }

    @Test
    fun `member and invalid requests are rejected before mutation`() {
        val memberFixture = fixture(MemberRole.MEMBER)

        assertThatThrownBy {
            memberFixture.service.create(
                API_USER_ID,
                CATEGORY_ID,
                "100",
                "MONTHLY",
                TODAY.dayOfMonth,
                TODAY,
            )
        }.isInstanceOf(MobileApiPlannedExpenseOwnerRequired::class.java)
        Mockito.verifyNoInteractions(memberFixture.recurring)

        val ownerFixture = fixture(MemberRole.OWNER)
        assertThatThrownBy {
            ownerFixture.service.create(
                API_USER_ID,
                CATEGORY_ID,
                "1.001",
                "MONTHLY",
                TODAY.dayOfMonth,
                TODAY,
            )
        }.isInstanceOf(InvalidPlannedExpenseRequest::class.java)
        assertThatThrownBy {
            ownerFixture.service.create(
                API_USER_ID,
                CATEGORY_ID,
                "100",
                "WEEKLY",
                8,
                TODAY,
            )
        }.isInstanceOf(InvalidPlannedExpenseRequest::class.java)
        Mockito.verifyNoInteractions(ownerFixture.recurring)
    }

    @Test
    fun `foreign and stale rule identifiers do not mutate plans`() {
        val fixture = fixture(MemberRole.OWNER)
        val ruleId = UUID.randomUUID()
        Mockito.`when`(fixture.recurring.rule(fixture.owner, ruleId)).thenReturn(null)

        assertThatThrownBy {
            fixture.service.update(API_USER_ID, ruleId, 0, "200")
        }.isInstanceOf(MobileApiPlannedExpenseNotFound::class.java)

        val current = ruleView(fixture.owner, id = ruleId, version = 2, amount = "100")
        Mockito.`when`(fixture.recurring.rule(fixture.owner, ruleId)).thenReturn(current.rule)
        assertThatThrownBy {
            fixture.service.delete(API_USER_ID, ruleId, 1)
        }.isInstanceOf(MobileApiPlannedExpenseConflict::class.java)

        Mockito.verify(fixture.recurring, Mockito.times(2)).rule(fixture.owner, ruleId)
        Mockito.verifyNoMoreInteractions(fixture.recurring)
    }

    @Test
    fun `owner closes a partially paid occurrence without adding another payment`() {
        val fixture = fixture(MemberRole.OWNER)
        val rule = ruleView(fixture.owner, version = 0, amount = "40500")
        val occurrence =
            RecurringOccurrence(
                UUID.randomUUID(),
                fixture.owner.householdId,
                rule.rule.id,
                CATEGORY_ID,
                Money.of("40500"),
                TODAY.minusMonths(1),
                version = 1,
                createdAt = NOW,
            )
        val current = RecurringOccurrenceView(occurrence, "Жильё", Money.of("39000"))
        val closed =
            RecurringOccurrenceView(
                occurrence.copy(status = OccurrenceStatus.PAID, version = 2),
                "Жильё",
                Money.of("39000"),
            )
        Mockito.`when`(fixture.recurring.occurrence(fixture.owner, occurrence.id)).thenReturn(current)
        Mockito
            .`when`(fixture.recurring.closeOccurrence(fixture.owner, occurrence.id, 1))
            .thenReturn(PlanChange.Applied(closed))
        Mockito.`when`(fixture.recurring.rule(fixture.owner, rule.rule.id)).thenReturn(rule.rule)

        val result = fixture.service.close(API_USER_ID, occurrence.id, 1)

        assertThat(result.asOf).isEqualTo(TODAY)
        assertThat(result.item.expense.occurrence.status).isEqualTo(OccurrenceStatus.PAID)
        assertThat(result.item.expense.paidAmount).isEqualTo(Money.of("39000"))
        assertThat(result.item.expense.remainingAmount).isEqualTo(Money.of("0"))
        Mockito.verify(fixture.recurring).closeOccurrence(fixture.owner, occurrence.id, 1)
    }

    private fun fixture(role: MemberRole): Fixture {
        val householdId = UUID.randomUUID()
        val memberId = UUID.randomUUID()
        val member = FamilyMember(memberId, householdId, API_USER_ID, role, false, STARTED_AT)
        val owner = OwnedLedger(householdId, memberId, API_USER_ID, STARTED_AT, role)
        val recurring = Mockito.mock(RecurringPlanService::class.java)
        return Fixture(
            PlannedExpenseMutationService(
                FakeAccess(member),
                FakeLedgerRepository(owner),
                recurring,
                CLOCK,
            ),
            recurring,
            owner,
        )
    }

    private fun ruleView(
        owner: OwnedLedger,
        id: UUID = UUID.randomUUID(),
        version: Long,
        amount: String,
    ) = RecurringRuleView(
        RecurringRule(
            id,
            owner.householdId,
            owner.memberId,
            CATEGORY_ID,
            RecurrenceFrequency.MONTHLY,
            TODAY.dayOfMonth,
            Money.of(amount),
            TODAY,
            version = version,
            createdAt = NOW,
            updatedAt = NOW,
        ),
        "Жильё",
    )

    private data class Fixture(
        val service: PlannedExpenseMutationService,
        val recurring: RecurringPlanService,
        val owner: OwnedLedger,
    )

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

        override fun operationHistory(
            householdId: UUID,
            before: Long,
            limit: Int,
            filter: OperationHistoryFilter,
        ): List<HistoryItem> = error("Not used")
    }

    companion object {
        private const val API_USER_ID = 991002L
        private val CATEGORY_ID = UUID.randomUUID()
        private val NOW = Instant.parse("2026-10-07T06:00:00Z")
        private val STARTED_AT = Instant.parse("2026-09-01T00:00:00Z")
        private val TODAY = LocalDate.parse("2026-10-07")
        private val CLOCK = Clock.fixed(NOW, ZoneOffset.UTC)
    }
}
