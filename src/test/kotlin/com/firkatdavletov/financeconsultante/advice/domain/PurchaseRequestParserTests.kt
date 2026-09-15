package com.firkatdavletov.financeconsultante.advice.domain

import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertIs

class PurchaseRequestParserTests {
    private val parser = PurchaseRequestParser()
    private val today = LocalDate.parse("2026-09-06")

    @Test
    fun `natural purchase question extracts amount and documents defaults`() {
        val result =
            assertIs<PurchaseRequestParseResult.Parsed>(
                parser.parseQuestion("можно купить ноутбук за 500000?", today),
            )

        assertThat(result.request.amount).isEqualTo(Money.of("500000"))
        assertThat(result.request.purchaseOn).isEqualTo(today)
        assertThat(result.request.dateWasAssumed).isTrue()
        assertThat(result.request.necessity).isEqualTo(PurchaseNecessity.UNSPECIFIED)
        assertThat(result.request.goalId).isNull()
    }

    @Test
    fun `command accepts date necessity linked goal and manual spending`() {
        val goal = UUID.randomUUID()
        val result =
            assertIs<PurchaseRequestParseResult.Parsed>(
                parser.parseCommand("500 000 2026-09-20 нужно goal=$goal daily=1250,50", today),
            )

        assertThat(result.request.amount).isEqualTo(Money.of("500000"))
        assertThat(result.request.purchaseOn).isEqualTo("2026-09-20")
        assertThat(result.request.dateWasAssumed).isFalse()
        assertThat(result.request.necessity).isEqualTo(PurchaseNecessity.NECESSARY)
        assertThat(result.request.goalId).isEqualTo(goal)
        assertThat(result.request.manualDailySpending).isEqualTo(Money.of("1250.50"))
    }

    @Test
    fun `ambiguous amount unsupported currency and invalid option request input`() {
        val checks =
            listOf(
                parser.parseQuestion("купить телефон 16 за 500000?", today),
                parser.parseQuestion("купить ноутбук за 500000 рублей?", today),
                parser.parseCommand("500000 когда-нибудь", today),
                parser.parseCommand("500000 daily=0", today),
            )

        assertThat(checks).allMatch { it is PurchaseRequestParseResult.NeedsInput }
        assertThat((checks[1] as PurchaseRequestParseResult.NeedsInput).message).contains("только в KZT")
    }
}
