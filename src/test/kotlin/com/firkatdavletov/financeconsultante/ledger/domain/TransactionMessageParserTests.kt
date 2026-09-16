package com.firkatdavletov.financeconsultante.ledger.domain

import com.firkatdavletov.financeconsultante.ledger.domain.MessageParseResult.Command
import com.firkatdavletov.financeconsultante.ledger.domain.MessageParseResult.NeedsClarification
import com.firkatdavletov.financeconsultante.ledger.domain.MessageParseResult.PurchaseQuestion
import com.firkatdavletov.financeconsultante.ledger.domain.MessageParseResult.RecordTransaction
import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertIs

@Suppress("TooManyFunctions")
class TransactionMessageParserTests {
    private val parser = TransactionMessageParser()
    private val today = LocalDate.parse("2026-03-01")

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "супермаркет 15000 | 15000 | EXPENSE | GROCERIES | Продукты",
            "продукты 700 | 700 | EXPENSE | GROCERIES | Продукты",
            "аптека 1300 | 1300 | EXPENSE | HEALTH | Здоровье",
            "зарплата 1300000 | 1300000 | INCOME | SALARY | Зарплата",
        ],
    )
    fun `reference messages identify amount direction and category`(
        input: String,
        amount: String,
        direction: Direction,
        category: String,
        displayName: String,
    ) {
        val result = record(input)
        assertThat(result.amount).isEqualTo(Money.of(amount))
        assertThat(result.direction).isEqualTo(direction)
        assertThat(result.category.name).isEqualTo(category)
        assertThat(result.category.displayName).isEqualTo(displayName)
        assertThat(result.occurredOn).isEqualTo(today)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "продукты 15 000 | 15000",
            "продукты 700,50 | 700.50",
            "продукты 700.50 | 700.50",
            "продукты 700.0 | 700",
            "продукты 700,00 | 700",
            "продукты 0,01 | 0.01",
            "продукты 99 999 999 999 999 999,99 | 99999999999999999.99",
            "продукты 99999999999999999.99 | 99999999999999999.99",
        ],
    )
    fun `decimal separators grouping and range boundaries preserve exact money`(
        input: String,
        amount: String,
    ) {
        assertThat(record(input).amount).isEqualTo(Money.of(amount))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "  ПРОДУКТЫ   15 000,50  ",
            "продукты\t15\t000,50",
            "\u00a0Продукты\u00a015\u00a0000,50\u00a0",
            "\u202fпродукты\u202f15\u202f000,50\u202f",
            "/EXPENSE\t15\u202f000,50\u00a0ПРОДУКТЫ",
        ],
    )
    fun `case tabs and nonbreaking spaces are normalized without losing cents`(input: String) {
        assertThat(record(input).amount).isEqualTo(Money.of("15000.50"))
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "KZT | KZT", "тенге | KZT", "₸ | KZT",
            "RUB | RUB", "рубль | RUB", "рубля | RUB", "рубли | RUB", "рублей | RUB", "₽ | RUB",
        ],
    )
    fun `explicit currencies work for income expenses and fallback commands`(
        token: String,
        currency: Currency,
    ) {
        for (input in listOf(
            "продукты 700.50 $token",
            "зарплата 700.50 $token",
            "/expense 700.50 $token здоровье",
            "/income 700.50 $token зарплата",
        )) {
            assertThat(record(input).amount).isEqualTo(Money.of("700.50", currency))
        }
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "/expense 5000 продукты | EXPENSE | GROCERIES",
            "/expense 5000 супермаркет | EXPENSE | GROCERIES",
            "/expense 5000 аптека | EXPENSE | HEALTH",
            "/expense 5000 здоровье | EXPENSE | HEALTH",
            "/income 5000 зарплата | INCOME | SALARY",
        ],
    )
    fun `fallback commands determine direction and canonical category`(
        input: String,
        direction: Direction,
        category: String,
    ) {
        val result = record(input)
        assertThat(result.direction).isEqualTo(direction)
        assertThat(result.category.name).isEqualTo(category)
        assertThat(result.amount).isEqualTo(Money.of("5000"))
    }

    @Test
    fun `multiword custom category works in short and explicit formats`() {
        val category =
            TransactionCategory(
                UUID.randomUUID().toString(),
                "Отдых и развлечение",
                Direction.EXPENSE,
                UUID.randomUUID(),
            )
        val customParser = TransactionMessageParser(mapOf("отдых и развлечение" to category))

        for (input in listOf("Отдых и развлечение 700", "/expense 700 Отдых и развлечение")) {
            val result = assertIs<RecordTransaction>(customParser.parse(input, today))
            assertThat(result.category).isEqualTo(category)
            assertThat(result.amount).isEqualTo(Money.of("700"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["/expense 100 зарплата", "/income 100 продукты", "/income 100 здоровье"])
    fun `explicit direction cannot contradict category`(input: String) {
        assertThat(parse(input)).isEqualTo(NeedsClarification(ClarificationReason.CATEGORY_MISMATCH))
    }

    @ParameterizedTest
    @ValueSource(strings = ["такси 5000", "перевод 5000", "/expense 5000 такси", "/income 5000 перевод"])
    fun `unknown categories and transfers require an explicit decision`(input: String) {
        assertThat(parse(input)).isEqualTo(NeedsClarification(ClarificationReason.UNKNOWN_CATEGORY))
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "0.00", "-1", "-0.01", "700.001", "700,500", "100000000000000000"])
    fun `nonpositive excessive precision and overflowing amounts are never rounded or recorded`(amount: String) {
        for (input in listOf("продукты $amount", "/income $amount зарплата")) {
            assertThat(parse(input)).isEqualTo(NeedsClarification(ClarificationReason.INVALID_AMOUNT))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["USD", "EUR", "BYN", "доллары", "евро", "$", "€"])
    fun `unsupported currency never becomes default KZT`(currency: String) {
        for (input in listOf("продукты 500 $currency", "/income 500 $currency зарплата")) {
            assertThat(parse(input)).isEqualTo(NeedsClarification(ClarificationReason.UNSUPPORTED_CURRENCY))
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "такси", "продукты", "700", "/expense", "/income 500", "/expense продукты 500",
            "продукты 1 23", "продукты 15 00", "продукты 700 50", "продукты 1,000.50", "продукты 1.000,50",
            "продукты 1e3", "продукты NaN", "продукты Infinity", "продукты 15к", "продукты +700",
            "продукты .50", "продукты 700,", "продукты 700 .50", "продукты ７００", "продукты ٧٠٠",
            "продукты 500 KZT RUB", "/expense 500 USD", "продукты 500 USD продукты", "продукты 500rub",
            "продукты 700 аптека 1300", "продукты 700, аптека 1300", "продукты 700 и аптека 1300",
            "продукты 700; зарплата 5000", "продукты 700 + аптека 1300", "продукты 700 /income 100 зарплата",
            "/expense 700 продукты 1300 аптека", "/income 100 зарплата и 200 зарплата",
            "не покупал продукты 700", "зарплата 100 ожидается", "вернули продукты 700", "продукты 700 завтра утром",
            "продукты 700 вчера сегодня", "продукты 700 01.03", "продукты 700 2026-03-01T10:00:00Z",
            "продукты 700\u0000", "продукты\u200b 700", "/expense 700 продукты?",
            "хочу продукты 700", "продукты 700?", "зарплата 1300000?",
        ],
    )
    fun `incomplete ambiguous malformed and multiple operations require clarification`(input: String) {
        assertIs<NeedsClarification>(parse(input))
    }

    @ParameterizedTest
    @ValueSource(strings = ["\n", "\r\n", "\r", "\u000b", "\u000c", "\u0085", "\u2028", "\u2029"])
    fun `line separators cannot merge operations or groups of digits`(separator: String) {
        for (input in listOf("продукты 700${separator}аптека 100", "продукты 15${separator}000")) {
            assertThat(parse(input)).isEqualTo(NeedsClarification(ClarificationReason.MULTILINE_INPUT))
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "купить ноутбук за 500000?", "купить ноутбук за 500000", "можно купить продукты 700",
            "планирую купить продукты 700", "стоит покупать ноутбук за 500000",
        ],
    )
    fun `purchase intentions and questions are separate from recording`(input: String) {
        assertThat(parse(input)).isEqualTo(PurchaseQuestion)
    }

    @ParameterizedTest
    @ValueSource(strings = ["/help", "/start", "/balance", "/unknown"])
    fun `commands take precedence over money in their arguments`(name: String) {
        assertThat(parse("$name продукты 700?")).isEqualTo(Command(name, "продукты 700?"))
        assertThat(parse(name)).isEqualTo(Command(name, ""))
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "сегодня | 2026-03-01", "вчера | 2026-02-28", "2026-03-01 | 2026-03-01",
            "28.02.2026 | 2026-02-28", "2024-02-29 | 2024-02-29", "29.02.2024 | 2024-02-29",
        ],
    )
    fun `relative and explicit dates work at message edges and in commands`(
        token: String,
        expected: String,
    ) {
        for (input in listOf(
            "$token продукты 700 RUB",
            "продукты 700 RUB $token",
            "/income 700 зарплата $token",
            "/expense $token 700 здоровье",
        )) {
            assertThat(record(input).occurredOn).isEqualTo(LocalDate.parse(expected))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["2026-02-29", "29.02.2026", "31.04.2026", "2026-13-01", "00.03.2026", "0000-01-01"])
    fun `invalid dates are not silently adjusted`(date: String) {
        assertThat(parse("продукты 700 $date")).isEqualTo(NeedsClarification(ClarificationReason.INVALID_DATE))
    }

    @ParameterizedTest
    @ValueSource(strings = ["завтра", "2026-03-02", "02.03.2026", "9999-12-31"])
    fun `future income and expenses require planning or clarification`(date: String) {
        for (input in listOf("продукты 700 $date", "зарплата 700 $date", "/income 700 зарплата $date")) {
            assertThat(parse(input)).isEqualTo(NeedsClarification(ClarificationReason.FUTURE_DATE))
        }
    }

    @Test
    fun `local date is supplied by caller including midnight and year boundary`() {
        val zone = ZoneId.of("Asia/Almaty")
        val beforeMidnight = Clock.fixed(Instant.parse("2025-12-31T18:59:59Z"), zone)
        val afterMidnight = Clock.fixed(Instant.parse("2025-12-31T19:00:00Z"), zone)
        assertThat(parser.parse("продукты 700 2026-01-01", LocalDate.now(beforeMidnight)))
            .isEqualTo(NeedsClarification(ClarificationReason.FUTURE_DATE))
        val result = assertIs<RecordTransaction>(parser.parse("продукты 700 вчера", LocalDate.now(afterMidnight)))
        assertThat(result.occurredOn).isEqualTo(LocalDate.parse("2025-12-31"))
    }

    @Test
    fun `blank and oversized inputs have bounded and safe failure results`() {
        assertThat(parse(" \t\u00a0")).isEqualTo(NeedsClarification(ClarificationReason.EMPTY_INPUT))
        assertThat(parse("продукты " + "9".repeat(512)))
            .isEqualTo(NeedsClarification(ClarificationReason.INPUT_TOO_LONG))
    }

    private fun parse(input: String): MessageParseResult = parser.parse(input, today)

    private fun record(input: String): RecordTransaction = assertIs<RecordTransaction>(parse(input))
}
