package com.firkatdavletov.financeconsultante.telegram.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class RecurringPaymentCallbacksTests {
    @Test
    fun `callback round trips two UUIDs and maximum version within Telegram limit`() {
        val occurrenceId = UUID.fromString("12345678-1234-5678-9abc-def012345678")
        val entryId = UUID.fromString("87654321-4321-8765-cba9-876543210fed")

        val data = RecurringPaymentCallbacks.data(occurrenceId, Long.MAX_VALUE, entryId)

        assertThat(data.toByteArray(Charsets.UTF_8)).hasSizeLessThanOrEqualTo(64)
        assertThat(RecurringPaymentCallbacks.parse(data)).isEqualTo(
            RecurringPaymentCallback(occurrenceId, Long.MAX_VALUE, entryId),
        )
    }

    @Test
    fun `malformed callbacks are rejected`() {
        assertThat(RecurringPaymentCallbacks.parse("rpm:broken:0:broken")).isNull()
        assertThat(RecurringPaymentCallbacks.parse("other:value")).isNull()
        assertThat(RecurringPaymentCallbacks.parse("rpm:EjRWeBI0VniavN7wEjRWeA:-1:h2VDIUMhh2XLqYdlQyEP7Q"))
            .isNull()
    }
}
