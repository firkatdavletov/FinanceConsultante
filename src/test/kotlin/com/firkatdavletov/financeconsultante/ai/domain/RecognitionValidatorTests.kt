package com.firkatdavletov.financeconsultante.ai.domain

import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class RecognitionValidatorTests {
    private val groceries =
        RecognitionCategory(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            "Продукты",
            Direction.EXPENSE,
        )
    private val salary =
        RecognitionCategory(
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            "Зарплата",
            Direction.INCOME,
        )
    private val input =
        RecognitionInput(
            "Вчера потратил 1 500,50 тенге на продукты",
            LocalDate.parse("2026-09-10"),
            listOf(groceries, salary),
        )

    @Test
    fun `valid candidate becomes a proposal without writing money`() {
        val result =
            validator.validate(
                input,
                candidate(
                    amount = "1500.50",
                    categoryId = groceries.id.toString(),
                    occurredOn = "2026-09-09",
                ),
            )

        assertThat(result).isInstanceOf(RecognitionValidation.Valid::class.java)
        val proposal = (result as RecognitionValidation.Valid).proposal
        assertThat(proposal.amount.amount).isEqualByComparingTo("1500.50")
        assertThat(proposal.category.id).isEqualTo(groceries.id)
        assertThat(proposal.occurredOn).isEqualTo("2026-09-09")
        assertThat(proposal.normalizedDescription).isEqualTo("продуктов")
    }

    @Test
    fun `invented amount category currency date and incomplete result are rejected`() {
        val checks =
            listOf(
                candidate("9999", groceries.id.toString(), "2026-09-09"),
                candidate("1500.50", UUID.randomUUID().toString(), "2026-09-09"),
                candidate("1500.50", groceries.id.toString(), "2026-09-09", RecognitionCurrency.RUB),
                candidate("1500.50", groceries.id.toString(), "2026-09-10"),
                candidate("1500.50", groceries.id.toString(), "2026-09-09", clarify = true),
            )

        assertThat(checks.map { validator.validate(input, it) })
            .allMatch { it is RecognitionValidation.Invalid }
        assertThat((validator.validate(input, checks[0]) as RecognitionValidation.Invalid).code)
            .isEqualTo("invented_amount")
    }

    @Test
    fun `category direction must match the proposed operation type`() {
        val result =
            validator.validate(
                input,
                candidate(
                    "1500.50",
                    salary.id.toString(),
                    "2026-09-09",
                    operationType = RecognitionOperationType.EXPENSE,
                ),
            )

        assertThat(result).isEqualTo(RecognitionValidation.Invalid("category_direction"))
    }

    @Test
    fun `multiple amounts and unsupported source currency are rejected`() {
        val multiple = input.copy(message = "продукты 1000 и аптека 2000")
        val dollars = input.copy(message = "потратил 20 USD на продукты")

        assertThat(
            validator.validate(multiple, candidate("1000", groceries.id.toString(), "2026-09-10")),
        ).isEqualTo(RecognitionValidation.Invalid("invented_amount"))
        assertThat(
            validator.validate(dollars, candidate("20", groceries.id.toString(), "2026-09-10")),
        ).isEqualTo(RecognitionValidation.Invalid("currency"))
    }

    @Test
    fun `invented subscription description is not retained`() {
        val result =
            validator.validate(
                input,
                candidate("1500.50", groceries.id.toString(), "2026-09-09").copy(description = "netflix"),
            )

        assertThat(result).isEqualTo(RecognitionValidation.Invalid("invented_description"))
    }

    private fun candidate(
        amount: String,
        categoryId: String,
        occurredOn: String,
        currency: RecognitionCurrency = RecognitionCurrency.KZT,
        operationType: RecognitionOperationType = RecognitionOperationType.EXPENSE,
        clarify: Boolean = false,
    ) = RecognitionCandidate(
        RecognitionIntent.RECORD_TRANSACTION,
        operationType,
        amount,
        currency,
        categoryId,
        occurredOn,
        "Покупка продуктов",
        clarify,
    )

    companion object {
        private val validator = RecognitionValidator()
    }
}
