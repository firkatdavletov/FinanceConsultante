package com.firkatdavletov.financeconsultante.ledger.domain

import java.util.Locale

/** Extracts only the explicit category part of the supported "category amount" input. */
object CategoryAliasNormalizer {
    @Suppress("ReturnCount") // Commands, multiline text, and non-canonical messages must not become aliases.
    fun fromMessage(text: String): String? {
        if (text.startsWith('/') || LINE_BREAK.containsMatchIn(text)) return null
        val input = normalizeSpaces(text).lowercase(Locale.ROOT)
        val first = input.substringBefore(' ')
        val last = input.substringAfterLast(' ')
        val dateToken = listOf(first, last).firstOrNull(DATE_TOKEN::matches)
        val body =
            when (dateToken) {
                null -> input
                first -> input.removePrefix(dateToken).trim()
                else -> input.removeSuffix(dateToken).trim()
            }
        val phrase =
            SHORT_TRANSACTION
                .matchEntire(body)
                ?.groups
                ?.get("category")
                ?.value ?: return null
        return normalize(phrase)
    }

    fun normalize(value: String): String? {
        val normalized = normalizeSpaces(value).lowercase(Locale.ROOT)
        return normalized.takeIf { it.length in 1..MAX_LENGTH && CATEGORY.matches(it) }
    }

    private fun normalizeSpaces(value: String): String = HORIZONTAL_SPACE.replace(value.trim(), " ")

    private const val MAX_LENGTH = 100
    private const val WORD = "[a-zа-яё]+(?:-[a-zа-яё]+)*"
    private const val AMOUNT = "-?(?:[0-9]{1,3}(?: [0-9]{3})+|[0-9]+)(?:[.,][0-9]+)?"
    private val CATEGORY = Regex("$WORD(?: $WORD)*")
    private val SHORT_TRANSACTION = Regex("(?<category>$WORD(?: $WORD)*) $AMOUNT(?: [a-zа-яё]+|[₸₽$€])?")
    private val DATE_TOKEN = Regex("сегодня|вчера|завтра|[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{2}\\.[0-9]{2}\\.[0-9]{4}")
    private val HORIZONTAL_SPACE = Regex("[ \\t\\u00a0\\u202f]+")
    private val LINE_BREAK = Regex("[\\r\\n\\u000b\\u000c\\u0085\\u2028\\u2029]")
}
