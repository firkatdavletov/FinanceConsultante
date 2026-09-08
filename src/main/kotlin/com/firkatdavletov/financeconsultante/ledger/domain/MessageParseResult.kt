package com.firkatdavletov.financeconsultante.ledger.domain

import com.firkatdavletov.financeconsultante.shared.domain.Money
import java.time.LocalDate

sealed interface MessageParseResult {
    /** A parsed proposal; recording still requires the accounting use case and its checks. */
    data class RecordTransaction(
        val amount: Money,
        val category: TransactionCategory,
        val occurredOn: LocalDate,
    ) : MessageParseResult {
        val direction: Direction get() = category.direction

        init {
            amount.requirePositive()
        }
    }

    data object PurchaseQuestion : MessageParseResult

    data class Command(
        val name: String,
        val arguments: String,
    ) : MessageParseResult

    data class NeedsClarification(
        val reason: ClarificationReason,
    ) : MessageParseResult
}

/** Stable dictionary keys, independent of the household's persistent category IDs. */
enum class TransactionCategory(
    val displayName: String,
    val direction: Direction,
) {
    GROCERIES("Продукты", Direction.EXPENSE),
    HEALTH("Здоровье", Direction.EXPENSE),
    SALARY("Зарплата", Direction.INCOME),
}

enum class ClarificationReason(
    val message: String,
) {
    EMPTY_INPUT("Введите одну операцию с суммой и категорией."),
    INPUT_TOO_LONG("Сократите сообщение до 512 символов и одной операции."),
    MULTILINE_INPUT("Введите одну операцию в одной строке; разделять сумму переносом строки нельзя."),
    INVALID_FORMAT("Уточните одну операцию: /expense <сумма> [валюта] <категория> или /income в том же формате."),
    INVALID_AMOUNT("Сумма должна быть больше нуля, до двух дробных знаков и не больше 99 999 999 999 999 999,99."),
    UNSUPPORTED_CURRENCY("Поддерживаются только KZT/тенге и RUB/рубли."),
    UNKNOWN_CATEGORY("Уточните категорию: продукты, здоровье или зарплата."),
    CATEGORY_MISMATCH("Категория зарплата относится к доходам, продукты и здоровье — к расходам."),
    INVALID_DATE("Укажите действительную дату: ГГГГ-ММ-ДД, ДД.ММ.ГГГГ, сегодня или вчера."),
    FUTURE_DATE("Будущая операция требует планирования или уточнения; фактическое движение ещё не подтверждено."),
}
