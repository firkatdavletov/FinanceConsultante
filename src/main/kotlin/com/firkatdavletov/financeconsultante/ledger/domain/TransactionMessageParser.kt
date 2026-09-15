package com.firkatdavletov.financeconsultante.ledger.domain

import com.firkatdavletov.financeconsultante.ledger.domain.MessageParseResult.Command
import com.firkatdavletov.financeconsultante.ledger.domain.MessageParseResult.NeedsClarification
import com.firkatdavletov.financeconsultante.ledger.domain.MessageParseResult.PurchaseQuestion
import com.firkatdavletov.financeconsultante.ledger.domain.MessageParseResult.RecordTransaction
import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import java.time.DateTimeException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/** Parses complete messages only. The caller supplies today's date in the budget's time zone. */
class TransactionMessageParser(
    private val categories: Map<String, TransactionCategory> = DEFAULT_CATEGORIES,
) {
    fun parse(
        text: String,
        today: LocalDate,
    ): MessageParseResult =
        when {
            text.length > MAX_INPUT_LENGTH -> {
                NeedsClarification(ClarificationReason.INPUT_TOO_LONG)
            }

            text.isBlank() -> {
                NeedsClarification(ClarificationReason.EMPTY_INPUT)
            }

            // Do not erase operation boundaries or turn "15\n000" into fifteen thousand.
            LINE_BREAK.containsMatchIn(text) -> {
                NeedsClarification(ClarificationReason.MULTILINE_INPUT)
            }

            else -> {
                val input = HORIZONTAL_SPACE.replace(text.trim(), " ").lowercase(Locale.ROOT)
                if (input.startsWith('/')) parseCommand(input, today) else parseMessage(input, today)
            }
        }

    private fun parseCommand(
        input: String,
        today: LocalDate,
    ): MessageParseResult {
        val command = input.substringBefore(' ')
        if (!COMMAND.matches(command)) return NeedsClarification(ClarificationReason.INVALID_FORMAT)
        val arguments = input.substringAfter(' ', "")
        return when (command) {
            "/expense" -> parseDatedTransaction(arguments, today, Direction.EXPENSE)
            "/income" -> parseDatedTransaction(arguments, today, Direction.INCOME)
            else -> Command(command, arguments)
        }
    }

    private fun parseMessage(
        input: String,
        today: LocalDate,
    ): MessageParseResult =
        when {
            PURCHASE_INTENT.containsMatchIn(input) -> PurchaseQuestion
            '?' in input -> NeedsClarification(ClarificationReason.INVALID_FORMAT)
            else -> parseDatedTransaction(input, today, null)
        }

    @Suppress("SwallowedException")
    private fun parseDatedTransaction(
        input: String,
        today: LocalDate,
        direction: Direction?,
    ): MessageParseResult =
        try {
            val first = input.substringBefore(' ')
            val last = input.substringAfterLast(' ')
            val dateToken = listOf(first, last).firstOrNull { DATE_TOKEN.matches(it) }
            val date =
                when (dateToken) {
                    null, "сегодня" -> today
                    "вчера" -> today.minusDays(1)
                    "завтра" -> today.plusDays(1)
                    else -> LocalDate.parse(dateToken, if ('.' in dateToken) RUSSIAN_DATE else ISO_DATE)
                }
            when {
                date.year < 1 -> {
                    NeedsClarification(ClarificationReason.INVALID_DATE)
                }

                date > today -> {
                    NeedsClarification(ClarificationReason.FUTURE_DATE)
                }

                else -> {
                    val body =
                        when (dateToken) {
                            null -> input
                            first -> input.removePrefix(dateToken).trim()
                            else -> input.removeSuffix(dateToken).trim()
                        }
                    parseTransaction(body, date, direction)
                }
            }
        } catch (_: DateTimeException) {
            NeedsClarification(ClarificationReason.INVALID_DATE)
        }

    private fun parseTransaction(
        input: String,
        date: LocalDate,
        direction: Direction?,
    ): MessageParseResult {
        val match =
            (if (direction == null) SHORT_TRANSACTION else EXPLICIT_TRANSACTION).matchEntire(input)
                ?: return NeedsClarification(ClarificationReason.INVALID_FORMAT)
        val category = categories[match.groups["category"]?.value]
        val currencyToken = match.groups["currency"]?.value.orEmpty()
        val currency = CURRENCIES[currencyToken]
        val amount = currency?.let { parseAmount(match.groups["amount"]?.value.orEmpty(), it) }
        return when {
            category == null -> {
                NeedsClarification(ClarificationReason.UNKNOWN_CATEGORY)
            }

            direction != null && direction != category.direction -> {
                NeedsClarification(
                    ClarificationReason.CATEGORY_MISMATCH,
                )
            }

            currency == null -> {
                NeedsClarification(ClarificationReason.UNSUPPORTED_CURRENCY)
            }

            amount == null -> {
                NeedsClarification(ClarificationReason.INVALID_AMOUNT)
            }

            else -> {
                RecordTransaction(
                    amount,
                    category,
                    date,
                    ExpenseDescriptionNormalizer.normalize(match.groups["category"]?.value.orEmpty()),
                )
            }
        }
    }

    @Suppress("SwallowedException")
    private fun parseAmount(
        text: String,
        currency: Currency,
    ): Money? =
        try {
            Money.of(text.replace(" ", "").replace(',', '.'), currency).requirePositive()
        } catch (_: IllegalArgumentException) {
            null
        }

    companion object {
        private const val MAX_INPUT_LENGTH = 512
        private const val AMOUNT = "(?<amount>-?(?:[0-9]{1,3}(?: [0-9]{3})+|[0-9]+)(?:[.,][0-9]+)?)"
        private const val CATEGORY = "(?<category>[a-zа-яё]+(?:-[a-zа-яё]+)*)"
        private const val CURRENCY = "(?: (?<currency>[a-zа-яё]+|[₸₽$€]))?"
        private val SHORT_TRANSACTION = Regex("$CATEGORY $AMOUNT$CURRENCY")
        private val EXPLICIT_TRANSACTION = Regex("$AMOUNT$CURRENCY $CATEGORY")
        private val COMMAND = Regex("/[a-z][a-z0-9_]*")
        private val HORIZONTAL_SPACE = Regex("[ \\t\\u00a0\\u202f]+")
        private val LINE_BREAK = Regex("[\\r\\n\\u000b\\u000c\\u0085\\u2028\\u2029]")
        private val PURCHASE_INTENT =
            Regex(
                "(?:^| )(?:купить|покупать|покупку|покупке|покупки|приобрести|приобретать|позволить|потянуть)" +
                    "(?: |[?]|$)",
            )
        private val DATE_TOKEN = Regex("сегодня|вчера|завтра|[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{2}\\.[0-9]{2}\\.[0-9]{4}")
        private val ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE
        private val RUSSIAN_DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT)
        val DEFAULT_CATEGORIES =
            mapOf(
                "супермаркет" to TransactionCategory.GROCERIES,
                "продукты" to TransactionCategory.GROCERIES,
                "аптека" to TransactionCategory.HEALTH,
                "здоровье" to TransactionCategory.HEALTH,
                "зарплата" to TransactionCategory.SALARY,
            )
        private val CURRENCIES =
            mapOf(
                "" to Currency.KZT,
                "kzt" to Currency.KZT,
                "тенге" to Currency.KZT,
                "₸" to Currency.KZT,
                "rub" to Currency.RUB,
                "рубль" to Currency.RUB,
                "рубля" to Currency.RUB,
                "рубли" to Currency.RUB,
                "рублей" to Currency.RUB,
                "₽" to Currency.RUB,
            )
    }
}
