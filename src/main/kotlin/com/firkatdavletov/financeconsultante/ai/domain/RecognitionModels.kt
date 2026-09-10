package com.firkatdavletov.financeconsultante.ai.domain

import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.ledger.domain.MessageParseResult.RecordTransaction
import com.firkatdavletov.financeconsultante.ledger.domain.TransactionCategory
import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import java.time.DateTimeException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.UUID

enum class RecognitionIntent {
    RECORD_TRANSACTION,
    PURCHASE_QUESTION,
    UNKNOWN,
}

enum class RecognitionOperationType {
    INCOME,
    EXPENSE,
    UNKNOWN,
}

enum class RecognitionCurrency {
    KZT,
    RUB,
    UNKNOWN,
}

data class RecognitionCategory(
    val id: UUID,
    val name: String,
    val direction: Direction,
)

data class RecognitionInput(
    val message: String,
    val today: LocalDate,
    val categories: List<RecognitionCategory>,
)

data class RecognitionCandidate(
    val intent: RecognitionIntent,
    val operationType: RecognitionOperationType,
    val amount: String,
    val currency: RecognitionCurrency,
    val categoryId: String,
    val occurredOn: String,
    val description: String,
    val needsClarification: Boolean,
)

sealed interface RecognitionValidation {
    data class Valid(
        val proposal: RecordTransaction,
    ) : RecognitionValidation

    data class Invalid(
        val code: String,
    ) : RecognitionValidation
}

class RecognitionValidator {
    @Suppress("ReturnCount", "CyclomaticComplexMethod") // Every branch protects a financial invariant.
    fun validate(
        input: RecognitionInput,
        candidate: RecognitionCandidate,
    ): RecognitionValidation {
        if (candidate.needsClarification || candidate.intent != RecognitionIntent.RECORD_TRANSACTION) {
            return invalid("needs_clarification")
        }
        val direction = candidate.operationType.direction ?: return invalid("operation_type")
        val currency = candidate.currency.currency ?: return invalid("currency")
        val amount = parseAmount(candidate.amount, currency) ?: return invalid("amount")
        val sourceAmounts = amountsFrom(input.message)
        if (sourceAmounts.size != 1 || sourceAmounts.none { it.compareTo(amount.amount) == 0 }) {
            return invalid("invented_amount")
        }
        val sourceCurrency = sourceCurrency(input.message) ?: return invalid("currency")
        if (currency != sourceCurrency) return invalid("invented_currency")
        val date = parseDate(candidate.occurredOn) ?: return invalid("date")
        if (date != expectedDate(input.message, input.today) || date > input.today) return invalid("invented_date")
        val categoryId =
            runCatching { UUID.fromString(candidate.categoryId) }.getOrNull()
                ?: return invalid("category")
        val category = input.categories.singleOrNull { it.id == categoryId } ?: return invalid("category")
        if (category.direction != direction) return invalid("category_direction")
        if (candidate.description.length > MAX_DESCRIPTION_LENGTH) return invalid("description")
        return RecognitionValidation.Valid(
            RecordTransaction(
                amount,
                TransactionCategory(category.id.toString(), category.name, category.direction, category.id),
                date,
            ),
        )
    }

    private fun amountsFrom(message: String): List<java.math.BigDecimal> {
        val withoutDates = DATE_TOKEN.replace(message.lowercase(), " ")
        return AMOUNT_TOKEN
            .findAll(withoutDates)
            .mapNotNull { token ->
                runCatching {
                    token.value
                        .replace(" ", "")
                        .replace("\u00a0", "")
                        .replace("\u202f", "")
                        .replace(',', '.')
                        .toBigDecimal()
                        .stripTrailingZeros()
                }.getOrNull()
            }.toList()
    }

    private fun expectedDate(
        message: String,
        today: LocalDate,
    ): LocalDate? {
        val tokens = DATE_TOKEN.findAll(message.lowercase()).map { it.value }.toList()
        val values =
            tokens
                .mapNotNull { token ->
                    when (token) {
                        "сегодня" -> today
                        "вчера" -> today.minusDays(1)
                        "завтра" -> today.plusDays(1)
                        else -> parseDate(token)
                    }
                }.distinct()
        if (values.size != tokens.distinct().size) return null
        return when (values.size) {
            0 -> today
            1 -> values.single()
            else -> null
        }
    }

    private fun parseAmount(
        value: String,
        currency: Currency,
    ): Money? =
        runCatching {
            Money.of(value.replace(" ", "").replace(',', '.'), currency).requirePositive()
        }.getOrNull()

    private fun sourceCurrency(message: String): Currency? {
        val normalized = message.lowercase()
        if (UNSUPPORTED_CURRENCY.containsMatchIn(normalized)) return null
        val rub = RUB_MARKER.containsMatchIn(normalized)
        val kzt = KZT_MARKER.containsMatchIn(normalized)
        return when {
            rub && kzt -> null
            rub -> Currency.RUB
            else -> Currency.KZT
        }
    }

    private fun parseDate(value: String): LocalDate? =
        try {
            LocalDate.parse(value, if ('.' in value) RUSSIAN_DATE else ISO_DATE)
        } catch (_: DateTimeException) {
            null
        }

    private fun invalid(code: String) = RecognitionValidation.Invalid(code)

    private val RecognitionOperationType.direction: Direction?
        get() =
            when (this) {
                RecognitionOperationType.INCOME -> Direction.INCOME
                RecognitionOperationType.EXPENSE -> Direction.EXPENSE
                RecognitionOperationType.UNKNOWN -> null
            }

    private val RecognitionCurrency.currency: Currency?
        get() =
            when (this) {
                RecognitionCurrency.KZT -> Currency.KZT
                RecognitionCurrency.RUB -> Currency.RUB
                RecognitionCurrency.UNKNOWN -> null
            }

    companion object {
        private const val MAX_DESCRIPTION_LENGTH = 160
        private val AMOUNT_TOKEN = Regex("[0-9]+(?:[ \\u00a0\\u202f][0-9]{3})*(?:[.,][0-9]+)?")
        private val DATE_TOKEN =
            Regex("сегодня|вчера|завтра|[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{2}\\.[0-9]{2}\\.[0-9]{4}")
        private val RUB_MARKER = Regex("(?:^|[^a-zа-яё])(?:rub|руб(?:ль|ля|ли|лей)?|₽)(?:$|[^a-zа-яё])")
        private val KZT_MARKER = Regex("(?:^|[^a-zа-яё])(?:kzt|тенге|₸)(?:$|[^a-zа-яё])")
        private val UNSUPPORTED_CURRENCY =
            Regex("(?:^|[^a-zа-яё])(?:usd|eur|доллар(?:а|ов)?|евро|[$€])(?:$|[^a-zа-яё])")
        private val ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE
        private val RUSSIAN_DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT)
    }
}
