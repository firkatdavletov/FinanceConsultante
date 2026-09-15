package com.firkatdavletov.financeconsultante.advice.domain

import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import java.time.DateTimeException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import java.util.UUID

sealed interface PurchaseRequestParseResult {
    data class Parsed(
        val request: PurchaseRequest,
    ) : PurchaseRequestParseResult

    data class NeedsInput(
        val message: String,
    ) : PurchaseRequestParseResult
}

class PurchaseRequestParser {
    @Suppress("ReturnCount") // Malformed transport input exits before a purchase request is created.
    fun parseCommand(
        arguments: String,
        today: LocalDate,
    ): PurchaseRequestParseResult {
        val normalized = normalize(arguments)
        val amountMatch =
            COMMAND_AMOUNT.matchEntire(normalized)
                ?: return needs("Укажите сумму покупки в KZT.")
        val amount =
            money(amountMatch.groups["amount"]?.value.orEmpty())
                ?: return needs("Сумма должна быть положительной, в KZT и с точностью до двух знаков.")
        return parseOptions(amount, amountMatch.groups["options"]?.value.orEmpty(), today)
    }

    @Suppress("ReturnCount") // Each ambiguous field has a specific safe clarification.
    fun parseQuestion(
        text: String,
        today: LocalDate,
    ): PurchaseRequestParseResult {
        val normalized = normalize(text)
        if (UNSUPPORTED_CURRENCY.containsMatchIn(normalized)) {
            return needs("Анализ покупки сейчас выполняется только в KZT.")
        }
        val goal =
            goal(normalized) ?: if (GOAL_MARKER.containsMatchIn(normalized)) {
                return needs("Для связи с целью укажите её UUID: goal=<ID из /goals>.")
            } else {
                null
            }
        val dateResult = dates(normalized, today)
        if (dateResult == null) return needs("Укажите одну действительную дату покупки.")
        val daily = optionalMoney(DAILY_OPTION, normalized)
        if (DAILY_OPTION.containsMatchIn(normalized) && daily == null) {
            return needs("После daily= укажите положительную сумму KZT в день.")
        }
        val stripped =
            normalized
                .replace(GOAL_OPTION, " ")
                .replace(DAILY_OPTION, " ")
                .replace(DATE_TOKEN, " ")
        val amountTokens = AMOUNT_TOKEN.findAll(stripped).map { it.value }.toList()
        if (amountTokens.size != 1) return needs("Укажите ровно одну сумму покупки в KZT.")
        val amount =
            money(amountTokens.single())
                ?: return needs("Сумма должна быть положительной и с точностью до двух знаков.")
        return PurchaseRequestParseResult.Parsed(
            PurchaseRequest(
                amount,
                dateResult.date,
                necessity(normalized),
                goal,
                daily,
                dateResult.assumed,
            ),
        )
    }

    @Suppress(
        "CyclomaticComplexMethod",
        "ReturnCount",
    ) // The closed option grammar rejects invalid values, duplicates, and unknown tokens at their boundary.
    private fun parseOptions(
        amount: Money,
        options: String,
        today: LocalDate,
    ): PurchaseRequestParseResult {
        var date = today
        var dateWasAssumed = true
        var necessity = PurchaseNecessity.UNSPECIFIED
        var goalId: UUID? = null
        var daily: Money? = null
        var currencySeen = false
        options.split(' ').filter(String::isNotEmpty).forEach { option ->
            when {
                option in KZT_MARKERS && !currencySeen -> {
                    currencySeen = true
                }

                parseDate(option, today) != null && dateWasAssumed -> {
                    date = checkNotNull(parseDate(option, today))
                    dateWasAssumed = false
                }

                option in NECESSARY_MARKERS && necessity == PurchaseNecessity.UNSPECIFIED -> {
                    necessity = PurchaseNecessity.NECESSARY
                }

                option in OPTIONAL_MARKERS && necessity == PurchaseNecessity.UNSPECIFIED -> {
                    necessity = PurchaseNecessity.OPTIONAL
                }

                GOAL_OPTION.matches(option) && goalId == null -> {
                    goalId = goal(option)
                }

                DAILY_OPTION.matches(option) && daily == null -> {
                    daily =
                        optionalMoney(DAILY_OPTION, option)
                            ?: return needs("После daily= укажите положительную сумму KZT в день.")
                }

                else -> {
                    return needs("Не удалось разобрать параметр «$option».")
                }
            }
        }
        return PurchaseRequestParseResult.Parsed(
            PurchaseRequest(amount, date, necessity, goalId, daily, dateWasAssumed),
        )
    }

    @Suppress("ReturnCount") // Zero, one, and multiple date tokens are intentionally distinct outcomes.
    private fun dates(
        text: String,
        today: LocalDate,
    ): ParsedDate? {
        val tokens =
            DATE_TOKEN
                .findAll(text)
                .map { it.value }
                .distinct()
                .toList()
        if (tokens.isEmpty()) return ParsedDate(today, assumed = true)
        if (tokens.size != 1) return null
        return parseDate(tokens.single(), today)?.let { ParsedDate(it, assumed = false) }
    }

    @Suppress("SwallowedException")
    private fun parseDate(
        token: String,
        today: LocalDate,
    ): LocalDate? =
        try {
            when (token) {
                "сегодня" -> today
                "завтра" -> today.plusDays(1)
                else -> LocalDate.parse(token, if ('.' in token) RUSSIAN_DATE else ISO_DATE)
            }
        } catch (_: DateTimeException) {
            null
        }

    private fun necessity(text: String): PurchaseNecessity =
        when {
            NECESSARY_WORDS.containsMatchIn(text) -> PurchaseNecessity.NECESSARY
            OPTIONAL_WORDS.containsMatchIn(text) -> PurchaseNecessity.OPTIONAL
            else -> PurchaseNecessity.UNSPECIFIED
        }

    private fun goal(text: String): UUID? =
        GOAL_OPTION.find(text)?.groups?.get("goal")?.value?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
        }

    private fun optionalMoney(
        pattern: Regex,
        text: String,
    ): Money? =
        pattern
            .find(text)
            ?.groups
            ?.get("daily")
            ?.value
            ?.let(::money)

    @Suppress("SwallowedException")
    private fun money(value: String): Money? =
        try {
            Money.of(value.replace(" ", "").replace(',', '.'), Currency.KZT).requirePositive()
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun normalize(value: String): String = HORIZONTAL_SPACE.replace(value.trim(), " ").lowercase(Locale.ROOT)

    private fun needs(reason: String) = PurchaseRequestParseResult.NeedsInput("$reason\n$HELP")

    private data class ParsedDate(
        val date: LocalDate,
        val assumed: Boolean,
    )

    companion object {
        const val HELP =
            "Формат: /purchase <сумма KZT> [сегодня|завтра|ГГГГ-ММ-ДД] " +
                "[нужно|хочу] [goal=<UUID>] [daily=<KZT в день>]."
        private const val AMOUNT = "(?:[0-9]{1,3}(?: [0-9]{3})+|[0-9]+)(?:[.,][0-9]{1,2})?"
        private const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        private val COMMAND_AMOUNT = Regex("^(?<amount>$AMOUNT)(?: (?<options>.*))?$")
        private val AMOUNT_TOKEN = Regex("(?<![0-9a-zа-яё])$AMOUNT(?![0-9a-zа-яё])")
        private val GOAL_OPTION = Regex("(?:goal|цель)=(?<goal>$UUID_PATTERN)")
        private val GOAL_MARKER = Regex("(?:^| )(?:goal|цель)(?:[ =]|$)")
        private val DAILY_OPTION = Regex("(?:daily|лимит)=(?<daily>$AMOUNT)")
        private val DATE_TOKEN =
            Regex("сегодня|завтра|[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{2}\\.[0-9]{2}\\.[0-9]{4}")
        private val HORIZONTAL_SPACE = Regex("[ \\t\\u00a0\\u202f]+")
        private val UNSUPPORTED_CURRENCY =
            Regex("(?:^|[^a-zа-яё])(?:rub|руб(?:ль|ля|ли|лей)?|₽|usd|eur|доллар(?:а|ов)?|евро|[$€])(?:$|[^a-zа-яё])")
        private val KZT_MARKERS = setOf("kzt", "тенге", "₸")
        private val NECESSARY_MARKERS = setOf("нужно", "необходимо")
        private val OPTIONAL_MARKERS = setOf("хочу", "необязательно")
        private val NECESSARY_WORDS = Regex("(?:^| )(?:нужно|нужен|нужна|необходимо|обязательно)(?: |[?.!,]|$)")
        private val OPTIONAL_WORDS = Regex("(?:^| )(?:хочу|необязательно|желательно)(?: |[?.!,]|$)")
        private val ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE
        private val RUSSIAN_DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT)
    }
}
