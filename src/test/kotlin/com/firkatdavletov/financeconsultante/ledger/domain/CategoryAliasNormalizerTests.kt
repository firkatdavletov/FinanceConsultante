package com.firkatdavletov.financeconsultante.ledger.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class CategoryAliasNormalizerTests {
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "Автобус 500 | автобус",
            "  Кафе и бар   1 200,50   | кафе и бар",
            "вчера Развлечение 3000 | развлечение",
            "Такси 700 RUB 2026-09-19 | такси",
        ],
    )
    fun `extracts a normalized alias only from category and amount input`(
        input: String,
        expected: String,
    ) {
        assertThat(CategoryAliasNormalizer.fromMessage(input)).isEqualTo(expected)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Вчера потратил 500 на автобус",
            "/expense 500 автобус",
            "кафе 100\nтакси 200",
            "кафе? 100",
        ],
    )
    fun `does not learn an alias from free text commands or malformed input`(input: String) {
        assertThat(CategoryAliasNormalizer.fromMessage(input)).isNull()
    }
}
