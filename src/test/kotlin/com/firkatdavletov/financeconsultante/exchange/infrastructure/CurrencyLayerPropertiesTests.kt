package com.firkatdavletov.financeconsultante.exchange.infrastructure

import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.time.LocalTime

class CurrencyLayerPropertiesTests {
    @Test
    fun `enabled integration requires key and fetch before report`() {
        assertThatIllegalArgumentException().isThrownBy {
            CurrencyLayerProperties(enabled = true).validateEnabled()
        }
        assertThatIllegalArgumentException().isThrownBy {
            CurrencyLayerProperties(
                enabled = true,
                accessKey = "synthetic",
                fetchTime = LocalTime.of(8, 0),
            ).validateEnabled()
        }
    }
}
