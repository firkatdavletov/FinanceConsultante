package com.firkatdavletov.financeconsultante.recurring.domain

import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class RecurringExpenseDetectorTests {
    private val categoryId = UUID.randomUUID()

    @Test
    fun `three weekly charges become an explainable candidate`() {
        val observations =
            listOf(
                observation("2026-08-01", "1000"),
                observation("2026-08-08", "990"),
                observation("2026-08-15", "1000"),
            )

        val candidate = RecurringExpenseDetector.candidates(observations, emptyList(), POLICY).single()

        assertThat(candidate.frequency).isEqualTo(RecurrenceFrequency.WEEKLY)
        assertThat(candidate.scheduleValue).isEqualTo(6)
        assertThat(candidate.suggestedAmount.amount).isEqualByComparingTo("1000")
        assertThat(candidate.evidence.map(ExpenseObservation::entryId))
            .containsExactlyElementsOf(observations.map(ExpenseObservation::entryId))
    }

    @Test
    fun `monthly candidate tolerates a skipped month and last days of short months`() {
        val candidate =
            RecurringExpenseDetector
                .candidates(
                    listOf(
                        observation("2026-06-30", "5000"),
                        observation("2026-07-31", "5000"),
                        observation("2026-09-30", "5000"),
                    ),
                    emptyList(),
                    POLICY,
                ).single()

        assertThat(candidate.frequency).isEqualTo(RecurrenceFrequency.MONTHLY)
        assertThat(candidate.scheduleValue).isEqualTo(31)
    }

    @Test
    fun `random similar grocery purchases and short history are not candidates`() {
        val random =
            listOf(
                observation("2026-08-01", "1000", "продукты"),
                observation("2026-08-10", "1050", "продукты"),
                observation("2026-08-28", "980", "продукты"),
            )

        assertThat(RecurringExpenseDetector.candidates(random, emptyList(), POLICY)).isEmpty()
        assertThat(RecurringExpenseDetector.candidates(random.take(2), emptyList(), POLICY)).isEmpty()
    }

    @Test
    fun `existing matching rule prevents duplicate reservation`() {
        val observations =
            listOf(
                observation("2026-08-01", "1000"),
                observation("2026-08-08", "1000"),
                observation("2026-08-15", "1000"),
            )
        val now = Instant.parse("2026-09-01T00:00:00Z")
        val rule =
            RecurringRule(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                categoryId,
                RecurrenceFrequency.WEEKLY,
                6,
                Money.of("1000"),
                LocalDate.parse("2026-08-01"),
                createdAt = now,
            )

        assertThat(RecurringExpenseDetector.candidates(observations, listOf(rule), POLICY)).isEmpty()
    }

    @Test
    fun `candidate and existing rule expose price growth using exact amounts`() {
        val observations =
            listOf(
                observation("2026-08-01", "1000"),
                observation("2026-08-08", "1000"),
                observation("2026-08-15", "1100"),
            )
        val candidate = RecurringExpenseDetector.candidates(observations, emptyList(), POLICY).single()
        assertThat(candidate.previousTypicalAmount?.amount).isEqualByComparingTo("1000")
        assertThat(candidate.suggestedAmount.amount).isEqualByComparingTo("1100")

        val now = Instant.parse("2026-08-01T00:00:00Z")
        val rule =
            RecurringRule(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                categoryId,
                RecurrenceFrequency.WEEKLY,
                6,
                Money.of("1000"),
                LocalDate.parse("2026-08-01"),
                createdAt = now,
            )
        val payments =
            listOf("1000", "1000", "1200").mapIndexed { index, amount ->
                RulePaymentObservation(
                    rule.id,
                    UUID.randomUUID(),
                    LocalDate.parse("2026-08-01").plusWeeks(index.toLong()),
                    Money.of(amount),
                )
            }

        val increase = RecurringExpenseDetector.priceIncreases(listOf(rule), payments, POLICY).single()
        assertThat(increase.previousTypicalAmount.amount).isEqualByComparingTo("1000")
        assertThat(increase.latestAmount.amount).isEqualByComparingTo("1200")
    }

    private fun observation(
        date: String,
        amount: String,
        description: String = "стриминг",
    ) = ExpenseObservation(
        UUID.randomUUID(),
        categoryId,
        description,
        LocalDate.parse(date),
        Money.of(amount),
    )

    companion object {
        private val POLICY =
            RecurrenceDetectionPolicy(
                minimumOccurrences = 3,
                lookbackDays = 400,
                weeklyIntervalDays = 7,
                weeklyToleranceDays = 1,
                monthlyIntervalDays = 30,
                monthlyToleranceDays = 4,
                amountTolerancePercent = 20,
                priceIncreasePercent = 5,
                allowedSkippedPeriods = 1,
            )
    }
}
