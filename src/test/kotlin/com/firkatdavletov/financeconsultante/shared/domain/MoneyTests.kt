package com.firkatdavletov.financeconsultante.shared.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class MoneyTests {
    @ParameterizedTest
    @ValueSource(strings = ["700", "700.0", "700.00"])
    fun `equivalent input has equal value and hash`(value: String) {
        assertThat(Money.of(value)).isEqualTo(Money.of("700.00"))
        assertThat(Money.of(value).hashCode()).isEqualTo(Money.of("700.00").hashCode())
        assertThat(Money.of(value).amount.scale()).isEqualTo(2)
    }

    @ParameterizedTest
    @ValueSource(strings = ["1.001", "1.000", "100000000000000000", "-100000000000000000", "NaN", "Infinity"])
    fun `unsupported precision range and nonfinite input are rejected`(value: String) {
        assertThatIllegalArgumentException().isThrownBy { Money.of(value) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "-0.01"])
    fun `zero and negative money cannot be an operation`(value: String) {
        assertThatIllegalArgumentException().isThrownBy { Money.of(value).requirePositive() }
    }

    @Test
    fun `exact arithmetic preserves cents and negative balances`() {
        assertThat(Money.of("0.10") + Money.of("0.20")).isEqualTo(Money.of("0.30"))
        assertThat(Money.of("0") - Money.of("0.01")).isEqualTo(Money.of("-0.01"))
        assertThat(Money.of("99999999999999999.99").requirePositive().amount)
            .isEqualByComparingTo("99999999999999999.99")
    }

    @Test
    fun `mixed currencies and arithmetic overflow are rejected`() {
        assertThatIllegalArgumentException().isThrownBy { Money.of("1") + Money.of("1", Currency.RUB) }
        assertThatIllegalArgumentException().isThrownBy { Money.of("1") - Money.of("1", Currency.RUB) }
        assertThatIllegalArgumentException().isThrownBy { Money.of("99999999999999999.99") + Money.of("0.01") }
        assertThatIllegalArgumentException().isThrownBy { Currency.valueOf("USD") }
    }
}
