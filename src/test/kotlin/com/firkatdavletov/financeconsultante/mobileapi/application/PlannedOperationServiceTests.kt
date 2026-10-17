package com.firkatdavletov.financeconsultante.mobileapi.application

import com.firkatdavletov.financeconsultante.access.application.FamilyBudgetAccess
import com.firkatdavletov.financeconsultante.access.domain.FamilyMember
import com.firkatdavletov.financeconsultante.access.domain.MemberRole
import com.firkatdavletov.financeconsultante.ledger.application.LedgerInteractionRepository
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.recurring.application.PlannedExpenseSchedule
import com.firkatdavletov.financeconsultante.recurring.application.RecurringPlanService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class PlannedOperationServiceTests {
    private val access = Mockito.mock(FamilyBudgetAccess::class.java)
    private val ledger = Mockito.mock(LedgerInteractionRepository::class.java)
    private val recurring = Mockito.mock(RecurringPlanService::class.java)
    private val clock = Clock.fixed(Instant.parse("2026-12-31T20:00:00Z"), ZoneOffset.UTC)
    private val service = PlannedOperationService(access, ledger, recurring, clock)

    @ParameterizedTest
    @CsvSource(
        "2028-02-28,END_OF_MONTH,2028-02-29",
        "2027-02-28,END_OF_MONTH,2027-02-28",
        "2026-12-31,END_OF_MONTH,2026-12-31",
        "2028-02-29,END_OF_YEAR,2028-12-31",
        "2026-12-31,END_OF_YEAR,2026-12-31",
    )
    fun `presets include today and the last calendar day`(
        today: LocalDate,
        period: PlannedOperationPeriod,
        expectedEnd: LocalDate,
    ) {
        assertThat(PlannedOperationFilter(period = period).dates(today)).isEqualTo(today..expectedEnd)
    }

    @Test
    fun `custom period permits a single past day`() {
        val day = LocalDate.parse("2026-09-01")
        val filter = PlannedOperationFilter(period = PlannedOperationPeriod.CUSTOM, dateFrom = day, dateTo = day)

        assertThat(filter.dates(TODAY)).isEqualTo(day..day)
    }

    @Test
    fun `rejects incomplete conflicting reversed and unbounded filters`() {
        val invalid =
            listOf(
                PlannedOperationFilter(type = Direction.EXPENSE_REFUND),
                PlannedOperationFilter(dateFrom = TODAY),
                PlannedOperationFilter(dateTo = TODAY),
                PlannedOperationFilter(period = PlannedOperationPeriod.CUSTOM),
                PlannedOperationFilter(period = PlannedOperationPeriod.CUSTOM, dateFrom = TODAY),
                PlannedOperationFilter(period = PlannedOperationPeriod.CUSTOM, dateTo = TODAY),
                custom(TODAY, TODAY.minusDays(1)),
                custom(TODAY, TODAY.plusYears(5).plusDays(1)),
                custom(LocalDate.of(0, 1, 1), TODAY),
            )

        invalid.forEach { filter ->
            assertThatThrownBy { filter.dates(TODAY) }.isInstanceOf(InvalidPlannedOperationRequest::class.java)
        }
    }

    @Test
    fun `uses Almaty new year for member access and selected generation horizon`() {
        val owner = allowMember()
        val through = LocalDate.parse("2027-12-31")
        Mockito
            .`when`(recurring.plannedExpenses(owner, TODAY, through))
            .thenReturn(PlannedExpenseSchedule(TODAY, through, emptyList()))
        Mockito.`when`(recurring.expectedIncomeForecast(owner, through)).thenReturn(emptyList())

        val result =
            service.plannedOperations(
                USER_ID,
                PlannedOperationFilter(period = PlannedOperationPeriod.END_OF_YEAR),
            )

        assertThat(result.asOf).isEqualTo(TODAY)
        assertThat(result.dateFrom).isEqualTo(TODAY)
        assertThat(result.dateTo).isEqualTo(through)
        assertThat(result.items).isEmpty()
        assertThat(result.totals.expenses).isZero()
        assertThat(result.totals.incomes).isZero()
        Mockito.verify(recurring).plannedExpenses(owner, TODAY, through)
    }

    @Test
    fun `income filter also reads expenses to return totals for both types`() {
        val owner = allowMember()
        val through = LocalDate.parse("2027-01-31")
        Mockito.`when`(recurring.expectedIncomeForecast(owner, through)).thenReturn(emptyList())
        Mockito
            .`when`(recurring.plannedExpenses(owner, TODAY, through))
            .thenReturn(PlannedExpenseSchedule(TODAY, through, emptyList()))

        service.plannedOperations(USER_ID, PlannedOperationFilter(type = Direction.INCOME))

        Mockito.verify(recurring).expectedIncomeForecast(owner, through)
        Mockito.verify(recurring).plannedExpenses(owner, TODAY, through)
        Mockito.verifyNoMoreInteractions(recurring)
    }

    @Test
    fun `rejects inactive membership before reading the ledger or plans`() {
        assertThatThrownBy { service.plannedOperations(USER_ID, PlannedOperationFilter()) }
            .isInstanceOf(MobileApiAccessDenied::class.java)

        Mockito.verifyNoInteractions(ledger, recurring)
    }

    @Test
    fun `rejects mismatched household or member before reading plans`() {
        val owner = allowMember()
        listOf(owner.copy(householdId = UUID.randomUUID()), owner.copy(memberId = UUID.randomUUID())).forEach {
            Mockito.`when`(ledger.owned(USER_ID)).thenReturn(it)
            assertThatThrownBy { service.plannedOperations(USER_ID, PlannedOperationFilter()) }
                .isInstanceOf(MobileApiAccessDenied::class.java)
        }

        Mockito.verifyNoInteractions(recurring)
    }

    private fun allowMember(): OwnedLedger {
        val member =
            FamilyMember(UUID.randomUUID(), UUID.randomUUID(), USER_ID, MemberRole.MEMBER, false, clock.instant())
        val owner = OwnedLedger(member.householdId, member.id, USER_ID, clock.instant(), member.role)
        Mockito.`when`(access.activeMember(USER_ID)).thenReturn(member)
        Mockito.`when`(ledger.owned(USER_ID)).thenReturn(owner)
        return owner
    }

    private fun custom(
        from: LocalDate,
        to: LocalDate,
    ) = PlannedOperationFilter(period = PlannedOperationPeriod.CUSTOM, dateFrom = from, dateTo = to)

    companion object {
        private const val USER_ID = 991002L
        private val TODAY = LocalDate.parse("2027-01-01")
    }
}
