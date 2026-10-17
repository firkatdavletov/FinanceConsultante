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

class PlannedIncomeMutationServiceTests {
    @Test
    fun `owner creates and edits a planned income`() {
        val fixture = fixture(MemberRole.OWNER)
        val created = incomeView(fixture.owner, amount = "100", dueOn = TODAY.plusDays(1), version = 0)
        val updated =
            incomeView(
                fixture.owner,
                id = created.expectedIncome.id,
                categoryId = UPDATED_CATEGORY_ID,
                amount = "125.50",
                dueOn = TODAY.plusDays(2),
                version = 1,
            )
        Mockito
            .`when`(
                fixture.recurring.createExpectedIncome(
                    fixture.owner,
                    Money.of("100"),
                    TODAY.plusDays(1),
                    CATEGORY_ID,
                    TODAY,
                ),
            ).thenReturn(PlanChange.Applied(created))
        Mockito
            .`when`(fixture.recurring.expectedIncome(fixture.owner, created.expectedIncome.id))
            .thenReturn(created)
        Mockito
            .`when`(
                fixture.recurring.updateExpectedIncome(
                    fixture.owner,
                    created.expectedIncome.id,
                    0,
                    Money.of("125.50"),
                    UPDATED_CATEGORY_ID,
                    TODAY.plusDays(2),
                    TODAY,
                ),
            ).thenReturn(PlanChange.Applied(updated))

        val createResult =
            fixture.service.create(API_USER_ID, CATEGORY_ID, "100", TODAY.plusDays(1))
        val updateResult =
            fixture.service.update(
                API_USER_ID,
                created.expectedIncome.id,
                0,
                UPDATED_CATEGORY_ID,
                "125.50",
                TODAY.plusDays(2),
            )

        assertThat(createResult).isEqualTo(MutatedPlannedIncome(TODAY, created))
        assertThat(updateResult).isEqualTo(MutatedPlannedIncome(TODAY, updated))
    }

    @Test
    fun `member and invalid payloads are rejected before mutation`() {
        val memberFixture = fixture(MemberRole.MEMBER)
        assertThatThrownBy {
            memberFixture.service.create(API_USER_ID, CATEGORY_ID, "100", TODAY)
        }.isInstanceOf(MobileApiPlannedIncomeOwnerRequired::class.java)
        Mockito.verifyNoInteractions(memberFixture.recurring)

        val ownerFixture = fixture(MemberRole.OWNER)
        assertThatThrownBy {
            ownerFixture.service.create(API_USER_ID, CATEGORY_ID, "1.001", TODAY)
        }.isInstanceOf(InvalidPlannedIncomeRequest::class.java)
        assertThatThrownBy {
            ownerFixture.service.update(API_USER_ID, UUID.randomUUID(), 0, null, null, null)
        }.isInstanceOf(InvalidPlannedIncomeRequest::class.java)
        Mockito.verifyNoInteractions(ownerFixture.recurring)
    }

    @Test
    fun `foreign and stale identifiers do not mutate planned income`() {
        val fixture = fixture(MemberRole.OWNER)
        val expectedIncomeId = UUID.randomUUID()
        Mockito.`when`(fixture.recurring.expectedIncome(fixture.owner, expectedIncomeId)).thenReturn(null)

        assertThatThrownBy {
            fixture.service.update(API_USER_ID, expectedIncomeId, 0, null, "200", null)
        }.isInstanceOf(MobileApiPlannedIncomeNotFound::class.java)

        val current = incomeView(fixture.owner, id = expectedIncomeId, amount = "100", dueOn = TODAY, version = 2)
        Mockito.`when`(fixture.recurring.expectedIncome(fixture.owner, expectedIncomeId)).thenReturn(current)
        assertThatThrownBy {
            fixture.service.update(API_USER_ID, expectedIncomeId, 1, null, "200", null)
        }.isInstanceOf(MobileApiPlannedIncomeConflict::class.java)

        Mockito.verify(fixture.recurring, Mockito.times(2)).expectedIncome(fixture.owner, expectedIncomeId)
        Mockito.verifyNoMoreInteractions(fixture.recurring)
    }

    private fun fixture(role: MemberRole): Fixture {
        val householdId = UUID.randomUUID()
        val memberId = UUID.randomUUID()
        val member = FamilyMember(memberId, householdId, API_USER_ID, role, false, STARTED_AT)
        val owner = OwnedLedger(householdId, memberId, API_USER_ID, STARTED_AT, role)
        val recurring = Mockito.mock(RecurringPlanService::class.java)
        return Fixture(
            PlannedIncomeMutationService(
                FakeAccess(member),
                FakeLedgerRepository(owner),
                recurring,
                CLOCK,
            ),
            recurring,
            owner,
        )
    }

    private fun incomeView(
        owner: OwnedLedger,
        id: UUID = UUID.randomUUID(),
        categoryId: UUID = CATEGORY_ID,
        amount: String,
        dueOn: LocalDate,
        version: Long,
    ) = ExpectedIncomeView(
        ExpectedIncome(
            id,
            owner.householdId,
            owner.memberId,
            categoryId,
            Money.of(amount),
            dueOn,
            version = version,
            createdAt = NOW,
            updatedAt = NOW,
        ),
        "Зарплата",
        Money.of("0"),
        postedMatches = 0,
    )

    private data class Fixture(
        val service: PlannedIncomeMutationService,
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
        private val CATEGORY_ID = UUID.fromString("20000000-0000-0000-0000-000000000001")
        private val UPDATED_CATEGORY_ID = UUID.fromString("20000000-0000-0000-0000-000000000002")
        private val STARTED_AT = Instant.parse("2026-09-01T00:00:00Z")
        private val NOW = Instant.parse("2026-10-07T00:00:00Z")
        private val TODAY = LocalDate.parse("2026-10-07")
        private val CLOCK = Clock.fixed(NOW, ZoneOffset.UTC)
    }
}
