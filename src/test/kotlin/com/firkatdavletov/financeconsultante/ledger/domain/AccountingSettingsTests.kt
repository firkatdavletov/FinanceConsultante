package com.firkatdavletov.financeconsultante.ledger.domain

import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

class AccountingSettingsTests {
    @Test
    fun `confirmed defaults use KZT and eight oclock Almaty`() {
        val household = Household(UUID.randomUUID())
        assertThat(household.baseCurrency).isEqualTo(Currency.KZT)
        assertThat(household.timeZone).isEqualTo(ZoneId.of("Asia/Almaty"))
        assertThat(household.reportTime).isEqualTo(LocalTime.of(8, 0))
        assertThat(Currency.entries).containsExactly(Currency.KZT, Currency.RUB)
        assertThatIllegalArgumentException().isThrownBy { household.copy(baseCurrency = Currency.RUB) }
        assertThatIllegalArgumentException().isThrownBy { household.copy(timeZone = ZoneId.of("UTC")) }
        assertThatIllegalArgumentException().isThrownBy { household.copy(reportTime = LocalTime.of(8, 0, 1)) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "-1", "1.000000001", "100000000000"])
    fun `invalid exchange rates are rejected`(rate: String) {
        assertThatIllegalArgumentException().isThrownBy {
            DailyExchangeRate(LocalDate.parse("2026-03-01"), BigDecimal(rate), "TEST", Instant.EPOCH)
        }
    }

    @Test
    fun `opening RUB balance and cross household setup are rejected`() {
        val household = Household(UUID.randomUUID())
        val account = Account(UUID.randomUUID(), household.id, Money.of("0"), Instant.EPOCH)
        assertThatIllegalArgumentException().isThrownBy { account.copy(openingBalance = Money.of("0", Currency.RUB)) }
        assertThatIllegalArgumentException().isThrownBy {
            BudgetSetup(household, Member(UUID.randomUUID(), UUID.randomUUID(), 900001), account, emptyList())
        }
    }
}
