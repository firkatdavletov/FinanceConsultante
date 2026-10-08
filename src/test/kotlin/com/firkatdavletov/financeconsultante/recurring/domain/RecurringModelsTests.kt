package com.firkatdavletov.financeconsultante.recurring.domain

import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class RecurringModelsTests {
    @Test
    fun `monthly day 31 moves to the last day of a short month`() {
        val rule = rule(RecurrenceFrequency.MONTHLY, 31, LocalDate.parse("2027-01-29"))

        assertThat(rule.dueDatesThrough(LocalDate.parse("2027-03-31")))
            .containsExactly(
                LocalDate.parse("2027-01-31"),
                LocalDate.parse("2027-02-28"),
                LocalDate.parse("2027-03-31"),
            )
    }

    @Test
    fun `monthly days 29 to 31 preserve leap day`() {
        val rule = rule(RecurrenceFrequency.MONTHLY, 31, LocalDate.parse("2028-01-31"))

        assertThat(rule.dueDatesThrough(LocalDate.parse("2028-03-31")))
            .containsExactly(
                LocalDate.parse("2028-01-31"),
                LocalDate.parse("2028-02-29"),
                LocalDate.parse("2028-03-31"),
            )
    }

    @Test
    fun `weekly rule starts on the first requested weekday on or after start`() {
        val rule = rule(RecurrenceFrequency.WEEKLY, 1, LocalDate.parse("2026-09-02"))

        assertThat(rule.dueDatesThrough(LocalDate.parse("2026-09-16")))
            .containsExactly(LocalDate.parse("2026-09-07"), LocalDate.parse("2026-09-14"))
    }

    @Test
    fun `partial overpayment and cancellation determine remaining obligation`() {
        val occurrence = occurrence("100", LocalDate.parse("2026-09-05"))
        val partial = RecurringOccurrenceView(occurrence, "Связь", Money.of("40"))
        val overpaid = RecurringOccurrenceView(occurrence, "Связь", Money.of("120"))
        val paid = RecurringOccurrenceView(occurrence.copy(status = OccurrenceStatus.PAID), "Связь", Money.of("40"))
        val cancelled =
            RecurringOccurrenceView(occurrence.copy(status = OccurrenceStatus.CANCELLED), "Связь", Money.of("40"))

        assertThat(partial.remainingAmount).isEqualTo(Money.of("60"))
        assertThat(partial.overdue(LocalDate.parse("2026-09-06"))).isTrue()
        assertThat(overpaid.remainingAmount).isEqualTo(Money.of("0"))
        assertThat(overpaid.paidInFull).isTrue()
        assertThat(paid.remainingAmount).isEqualTo(Money.of("0"))
        assertThat(paid.paidInFull).isTrue()
        assertThat(paid.overdue(LocalDate.parse("2026-09-06"))).isFalse()
        assertThat(cancelled.remainingAmount).isEqualTo(Money.of("0"))
        assertThat(cancelled.overdue(LocalDate.parse("2026-09-06"))).isFalse()
    }

    @Test
    fun `received expected income is excluded from forecast regardless of actual amount`() {
        val expected =
            ExpectedIncome(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                Money.of("100"),
                LocalDate.parse("2026-09-10"),
                createdAt = Instant.parse("2026-09-01T00:00:00Z"),
            )

        assertThat(ExpectedIncomeView(expected, "Зарплата", Money.of("0"), 0).forecastAmount)
            .isEqualTo(Money.of("100"))
        assertThat(ExpectedIncomeView(expected, "Зарплата", Money.of("120"), 1).forecastAmount)
            .isEqualTo(Money.of("0"))
    }

    private fun rule(
        frequency: RecurrenceFrequency,
        scheduleValue: Int,
        startsOn: LocalDate,
    ): RecurringRule =
        RecurringRule(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            frequency,
            scheduleValue,
            Money.of("100"),
            startsOn,
            createdAt = Instant.parse("2026-09-01T00:00:00Z"),
        )

    private fun occurrence(
        amount: String,
        dueOn: LocalDate,
    ): RecurringOccurrence =
        RecurringOccurrence(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            Money.of(amount),
            dueOn,
            createdAt = Instant.parse("2026-09-01T00:00:00Z"),
        )
}
