package com.firkatdavletov.financeconsultante.telegram.application

import com.firkatdavletov.financeconsultante.advice.application.PurchaseAdviceMessages
import com.firkatdavletov.financeconsultante.advice.application.PurchaseAdviceResult
import com.firkatdavletov.financeconsultante.advice.application.PurchaseAdviceService
import com.firkatdavletov.financeconsultante.advice.domain.PurchaseRequestParseResult
import com.firkatdavletov.financeconsultante.advice.domain.PurchaseRequestParser
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import java.time.LocalDate

class TelegramPurchaseHandler(
    private val service: PurchaseAdviceService,
) {
    private val parser = PurchaseRequestParser()

    fun command(
        arguments: String,
        owner: OwnedLedger,
        today: LocalDate,
    ): TelegramReply = handle(parser.parseCommand(arguments, today), owner)

    fun question(
        text: String,
        owner: OwnedLedger,
        today: LocalDate,
    ): TelegramReply = handle(parser.parseQuestion(text, today), owner)

    private fun handle(
        parsed: PurchaseRequestParseResult,
        owner: OwnedLedger,
    ): TelegramReply =
        when (parsed) {
            is PurchaseRequestParseResult.NeedsInput -> {
                parsed.message.reply()
            }

            is PurchaseRequestParseResult.Parsed -> {
                when (val result = service.analyze(owner, parsed.request)) {
                    is PurchaseAdviceResult.NeedsInput -> {
                        "${result.message}\n${PurchaseRequestParser.HELP}".reply()
                    }

                    is PurchaseAdviceResult.Ready -> {
                        PurchaseAdviceMessages.render(result.advice).reply()
                    }
                }
            }
        }

    companion object {
        const val COMMAND = "/purchase"
    }
}
