package com.firkatdavletov.financeconsultante.goals.domain

import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class SavingsGoalTests {
    @Test
    fun `saving goal reports deterministic ceiling pace`() {
        val goal = goal(target = "100", allocated = "30", deadline = "2026-09-08")

        val pace = checkNotNull(goal.requiredPace(LocalDate.parse("2026-09-06")))

        assertThat(goal.state(LocalDate.parse("2026-09-06"))).isEqualTo(SavingsGoalState.SAVING)
        assertThat(goal.remainingAmount).isEqualTo(Money.of("70"))
        assertThat(pace.daysRemaining).isEqualTo(3)
        assertThat(pace.amountPerDay).isEqualTo(Money.of("23.34"))
    }

    @Test
    fun `achieved state wins after deadline while incomplete goal is overdue`() {
        val achieved = goal(target = "100", allocated = "100", deadline = "2026-09-05")
        val overdue = goal(target = "100", allocated = "99", deadline = "2026-09-05")
        val asOf = LocalDate.parse("2026-09-06")

        assertThat(achieved.state(asOf)).isEqualTo(SavingsGoalState.ACHIEVED)
        assertThat(achieved.requiredPace(asOf)).isNull()
        assertThat(overdue.state(asOf)).isEqualTo(SavingsGoalState.OVERDUE)
        assertThat(overdue.requiredPace(asOf)).isNull()
    }

    private fun goal(
        target: String,
        allocated: String,
        deadline: String,
    ): SavingsGoal =
        SavingsGoal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Синтетическая цель",
            Money.of(target),
            LocalDate.parse(deadline),
            2,
            Money.of(allocated),
            createdAt = Instant.parse("2026-09-01T00:00:00Z"),
        )
}
