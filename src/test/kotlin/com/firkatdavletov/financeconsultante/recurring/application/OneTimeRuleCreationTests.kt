package com.firkatdavletov.financeconsultante.recurring.application

import com.firkatdavletov.financeconsultante.ledger.application.AccountingService
import com.firkatdavletov.financeconsultante.ledger.application.BudgetRepository
import com.firkatdavletov.financeconsultante.ledger.application.LedgerRepository
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.ledger.domain.Account
import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import com.firkatdavletov.financeconsultante.recurring.domain.RecurrenceFrequency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class OneTimeRuleCreationTests {
    @Test
    fun `one-time creation uses the Almaty day rather than the UTC day`() {
        val fixture = fixture()
        val result = fixture.create(LocalDate.parse("2026-11-03"))

        assertThat(result).isInstanceOf(PlanChange.Rejected::class.java)
        Mockito.verifyNoInteractions(fixture.plans)
    }

    @Test
    fun `offline creation cannot predate accounting or its authored day`() {
        val fixture = fixture()
        listOf(
            "2026-10-31" to "2026-11-01",
            "2026-11-02" to "2026-11-03",
            "2026-11-05" to "2026-11-05",
        ).forEach { (startsOn, authoredOn) ->
            assertThat(fixture.create(LocalDate.parse(startsOn), LocalDate.parse(authoredOn)))
                .isInstanceOf(PlanChange.Rejected::class.java)
        }
        Mockito.verifyNoInteractions(fixture.plans)
    }

    private fun fixture(): Fixture {
        val started = Instant.parse("2026-10-31T19:00:00Z")
        val owner = OwnedLedger(UUID.randomUUID(), UUID.randomUUID(), 1, started)
        val category = Category(UUID.randomUUID(), owner.householdId, "Synthetic expense", CategoryKind.EXPENSE)
        val plans = Mockito.mock(RecurringPlanRepository::class.java)
        val budgets = Mockito.mock(BudgetRepository::class.java)
        Mockito
            .`when`(budgets.findOwnedAccount(owner.householdId, owner.memberId, lock = true))
            .thenReturn(Account(UUID.randomUUID(), owner.householdId, Money.of("0"), started))
        Mockito.`when`(budgets.findCategory(owner.householdId, category.id)).thenReturn(category)
        return Fixture(
            RecurringPlanService(
                plans,
                budgets,
                Mockito.mock(LedgerRepository::class.java),
                Mockito.mock(AccountingService::class.java),
                Clock.fixed(Instant.parse("2026-11-03T20:00:00Z"), ZoneOffset.UTC),
                Mockito.mock(IncomeRuleRepository::class.java),
            ),
            plans,
            owner,
            category.id,
        )
    }

    private data class Fixture(
        val service: RecurringPlanService,
        val plans: RecurringPlanRepository,
        val owner: OwnedLedger,
        val categoryId: UUID,
    ) {
        fun create(
            startsOn: LocalDate,
            authoredOn: LocalDate? = null,
        ) = service.createRule(
            owner,
            RecurrenceFrequency.ONCE,
            null,
            Money.of("100.00"),
            categoryId,
            startsOn,
            authoredOn = authoredOn,
        )
    }
}
