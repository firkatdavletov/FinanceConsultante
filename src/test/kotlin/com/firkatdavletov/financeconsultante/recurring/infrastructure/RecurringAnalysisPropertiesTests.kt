package com.firkatdavletov.financeconsultante.recurring.infrastructure

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RecurringAnalysisPropertiesTests {
    @Test
    fun `default policy fixes explainable detection tolerances`() {
        val policy = RecurringAnalysisProperties().toPolicy()

        assertThat(policy.minimumOccurrences).isEqualTo(3)
        assertThat(policy.weeklyIntervalDays).isEqualTo(7)
        assertThat(policy.weeklyToleranceDays).isEqualTo(1)
        assertThat(policy.monthlyIntervalDays).isEqualTo(30)
        assertThat(policy.monthlyToleranceDays).isEqualTo(4)
        assertThat(policy.amountTolerancePercent).isEqualTo(20)
        assertThat(policy.priceIncreasePercent).isEqualTo(5)
        assertThat(policy.allowedSkippedPeriods).isEqualTo(1)
    }
}
