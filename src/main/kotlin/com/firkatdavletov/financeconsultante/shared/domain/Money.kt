package com.firkatdavletov.financeconsultante.shared.domain

import java.math.BigDecimal

enum class Currency {
    KZT,
    RUB,
}

class Money private constructor(
    val amount: BigDecimal,
    val currency: Currency,
) {
    operator fun plus(other: Money): Money {
        require(currency == other.currency) { "Currency mismatch" }
        return of(amount + other.amount, currency)
    }

    operator fun minus(other: Money): Money {
        require(currency == other.currency) { "Currency mismatch" }
        return of(amount - other.amount, currency)
    }

    fun requirePositive(): Money {
        require(amount.signum() > 0) { "Amount must be positive" }
        return this
    }

    override fun equals(other: Any?): Boolean = other is Money && amount == other.amount && currency == other.currency

    override fun hashCode(): Int = 31 * amount.hashCode() + currency.hashCode()

    companion object {
        private const val SCALE = 2
        private val MAX_AMOUNT = BigDecimal("99999999999999999.99")

        fun of(
            amount: String,
            currency: Currency = Currency.KZT,
        ): Money = of(BigDecimal(amount), currency)

        fun of(
            amount: BigDecimal,
            currency: Currency = Currency.KZT,
        ): Money {
            require(amount.scale() <= SCALE) { "Amount precision exceeds two decimal places" }
            require(amount.abs() <= MAX_AMOUNT) { "Amount exceeds supported range" }
            return Money(amount.setScale(SCALE), currency)
        }
    }
}
