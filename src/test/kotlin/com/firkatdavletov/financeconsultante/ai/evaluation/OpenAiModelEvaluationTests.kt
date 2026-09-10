package com.firkatdavletov.financeconsultante.ai.evaluation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpenAiModelEvaluationTests {
    @Test
    fun `score normalizes decimal scale and compares safety fields exactly`() {
        val expected =
            EvaluationFields(
                intent = "RECORD_TRANSACTION",
                operationType = "EXPENSE",
                amount = "700.50",
                currency = "RUB",
                category = "Здоровье",
                occurredOn = "2026-09-09",
                needsClarification = false,
            )
        val actual = expected.copy(amount = "700.5")

        assertThat(score(expected, actual).all).isTrue()
        assertThat(score(expected, actual.copy(category = "Продукты")).all).isFalse()
    }

    @Test
    fun `nearest rank percentiles are deterministic`() {
        val values = listOf(120L, 80L, 100L, 90L)

        assertThat(percentile(values, 0.50)).isEqualTo(90)
        assertThat(percentile(values, 0.95)).isEqualTo(120)
        assertThat(percentile(emptyList(), 0.50)).isNull()
    }
}
