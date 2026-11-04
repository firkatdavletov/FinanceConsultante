package com.firkatdavletov.financeconsultante.recurring.domain

import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class IncomeRuleTests {
    @Test fun `monthly income retains anchor day across leap and short months and effective boundary`() {
        val rule = rule(IncomeFrequency.MONTHLY, "2028-01-31", "2028-02-01")
        assertThat(rule.dueDatesThrough(LocalDate.parse("2028-04-30")))
            .containsExactly(
                LocalDate.parse("2028-02-29"),
                LocalDate.parse("2028-03-31"),
                LocalDate.parse("2028-04-30"),
            )
        assertThat(rule.copy(incomeActive = false).dueDatesThrough(LocalDate.parse("2028-04-30"))).isEmpty()
    }

    @Test fun `biweekly schedule keeps anchor parity when editing effective date`() {
        assertThat(
            rule(IncomeFrequency.BIWEEKLY, "2026-09-01", "2026-09-06").dueDatesThrough(LocalDate.parse("2026-10-01")),
        ).containsExactly(LocalDate.parse("2026-09-15"), LocalDate.parse("2026-09-29"))
        assertThat(
            rule(IncomeFrequency.ONCE, "2026-09-01", "2026-09-06").dueDatesThrough(LocalDate.parse("2026-10-01")),
        ).isEmpty()
    }

    private fun rule(
        frequency: IncomeFrequency,
        anchor: String,
        effective: String,
    ) = IncomeRule(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        Money.of("100"),
        LocalDate.parse(anchor),
        "Synthetic",
        frequency,
        true,
        0,
        Instant.EPOCH,
        Instant.EPOCH,
        LocalDate.parse(effective),
    )
}
