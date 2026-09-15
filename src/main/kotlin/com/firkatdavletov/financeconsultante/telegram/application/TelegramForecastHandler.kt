package com.firkatdavletov.financeconsultante.telegram.application

import com.firkatdavletov.financeconsultante.analytics.application.BudgetForecastService
import com.firkatdavletov.financeconsultante.analytics.application.ForecastMessages
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money

class TelegramForecastHandler(
    private val service: BudgetForecastService,
) {
    @Suppress("ReturnCount") // Invalid optional transport input stops before the forecast query.
    fun command(
        arguments: String,
        owner: OwnedLedger,
    ): TelegramReply {
        if (arguments.isEmpty()) return ForecastMessages.forecast(service.forecast(owner)).reply()
        val amount = amount(arguments) ?: return HELP.reply()
        return ForecastMessages.forecast(service.forecast(owner, amount)).reply()
    }

    @Suppress("SwallowedException")
    private fun amount(text: String): Money? =
        try {
            if (!AMOUNT.matches(text)) null else Money.of(text.replace(',', '.'), Currency.KZT).requirePositive()
        } catch (_: IllegalArgumentException) {
            null
        }

    companion object {
        const val COMMAND = "/forecast"
        const val HELP =
            "Формат: /forecast или /forecast " +
                "<положительный лимит KZT в день>."
        private val AMOUNT = Regex("[0-9]+(?:[.,][0-9]{1,2})?")
    }
}
