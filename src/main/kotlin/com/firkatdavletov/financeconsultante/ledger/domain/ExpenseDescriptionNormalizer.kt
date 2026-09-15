package com.firkatdavletov.financeconsultante.ledger.domain

import java.util.Locale

/** Keeps only a short, non-sensitive label needed to compare recurring expenses. */
object ExpenseDescriptionNormalizer {
    const val MAX_LENGTH = 160

    fun normalize(value: String): String? {
        val normalized =
            NON_WORD
                .replace(value.lowercase(Locale.ROOT).replace('ё', 'е'), " ")
                .trim()
                .replace(MULTIPLE_SPACES, " ")
        return normalized.takeIf { it.isNotEmpty() && it.length <= MAX_LENGTH }
    }

    /** Accepts only meaningful AI description tokens that are present in the original message. */
    fun grounded(
        description: String,
        source: String,
    ): String? {
        val candidate = normalize(description)
        val sourceTokens = normalize(source)?.split(' ')?.toSet().orEmpty()
        val meaningful = candidate?.split(' ')?.filterNot(GENERIC_WORDS::contains).orEmpty()
        val isGrounded =
            meaningful.isNotEmpty() &&
                meaningful.all { token -> sourceTokens.any { groundedToken(token, it) } }
        return meaningful.takeIf { isGrounded }?.joinToString(" ")
    }

    private fun groundedToken(
        candidate: String,
        source: String,
    ): Boolean =
        candidate == source ||
            (
                candidate.length >= MIN_STEM_LENGTH && source.length >= MIN_STEM_LENGTH &&
                    candidate.commonPrefixWith(source).length >= MIN_STEM_LENGTH
            )

    private val NON_WORD = Regex("[^\\p{L}\\p{N}-]+")
    private val MULTIPLE_SPACES = Regex(" +")
    private val GENERIC_WORDS =
        setOf(
            "оплата",
            "платеж",
            "покупка",
            "расход",
            "трата",
            "за",
            "на",
        )
    private const val MIN_STEM_LENGTH = 5
}
