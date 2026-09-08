package com.firkatdavletov.financeconsultante.telegram.domain

import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate
import java.time.LocalTime

@Suppress("MagicNumber", "TooManyFunctions")
class SetupConversationTests {
    private val conversation = SetupConversation()
    private val today = LocalDate.of(2026, 3, 1)
    private val settings = BudgetSettings(Money.of("1234.50"), today, LocalTime.of(8, 0), Money.of("100"))

    @Test
    fun `onboarding commits only after all answers and keeps opening separate from reserve`() {
        var result = respond("/start")
        assertThat(result.reply).contains("KZT/RUB", "Asia/Almaty", "08:00", "банковский остаток")
        for (answer in listOf("-1 234,50", "2026-03-01", "07:45")) {
            result = respond(answer, result.dialog)
            assertThat(result.save).isNull()
        }
        result = respond("0", result.dialog)
        assertThat(
            result.save,
        ).isEqualTo(BudgetSettings(Money.of("-1234.50"), today, LocalTime.of(7, 45), Money.of("0")))
        assertThat(result.dialog.step).isEqualTo(SetupStep.IDLE)
    }

    @Test
    fun `start and settings preserve pending input while cancel discards the draft`() {
        val pending = respond("100", respond("/start").dialog).dialog
        assertThat(respond("/start", pending).dialog).isEqualTo(pending)
        assertThat(respond("/settings", pending).dialog).isEqualTo(pending)
        assertThat(respond("/help", pending).dialog).isEqualTo(pending)
        assertThat(respond("/cancel", pending).dialog).isEqualTo(SetupDialog())
    }

    @ParameterizedTest
    @ValueSource(strings = ["1.001", "1e3", "100 RUB", "NaN", "999999999999999999", "1 2", "", "--1"])
    fun `invalid amounts do not advance or save`(input: String) {
        val dialog = SetupDialog(step = SetupStep.OPENING, initialSetup = true)
        val result = respond(input, dialog)
        assertThat(result.dialog).isEqualTo(dialog)
        assertThat(result.save).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["2026-03-02", "2026-02-29", "01.03.2026", "0000-01-01"])
    fun `future and invalid dates leave the opening draft intact`(input: String) {
        val dialog = SetupDialog(step = SetupStep.DATE, initialSetup = true, openingBalance = Money.of("1"))
        assertThat(respond(input, dialog).dialog).isEqualTo(dialog)
    }

    @ParameterizedTest
    @ValueSource(strings = ["24:00", "8:00", "08:00:01", "08:60"])
    fun `report time uses strict hours and minutes`(input: String) {
        val pending = conversation.respond("/report_time", SetupDialog(), settings, false, today).dialog
        assertThat(respond(input, pending).save).isNull()
        assertThat(respond(input, pending).dialog).isEqualTo(pending)
    }

    @Test
    fun `negative reserve is rejected and changing reserve does not change opening`() {
        val pending = conversation.respond("/reserve", SetupDialog(), settings, true, today).dialog
        assertThat(respond("-1", pending).save).isNull()
        assertThat(respond("200", pending).save).isEqualTo(settings.copy(cashReserve = Money.of("200")))
    }

    @Test
    fun `opening edits are prohibited after entries and fixed currency is explained`() {
        val result = conversation.respond("/opening", SetupDialog(), settings, true, today)
        assertThat(result.dialog.step).isEqualTo(SetupStep.IDLE)
        assertThat(result.reply).contains("нельзя")
        assertThat(respond("/currency").reply).contains("нельзя просто переименовать")
    }

    private fun respond(
        text: String,
        dialog: SetupDialog = SetupDialog(),
    ): SetupDecision = conversation.respond(text, dialog, null, false, today)
}
