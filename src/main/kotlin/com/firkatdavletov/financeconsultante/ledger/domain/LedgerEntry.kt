package com.firkatdavletov.financeconsultante.ledger.domain

import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class Direction(
    val categoryKind: CategoryKind,
) {
    INCOME(CategoryKind.INCOME),
    EXPENSE(CategoryKind.EXPENSE),
    EXPENSE_REFUND(CategoryKind.EXPENSE),
}

enum class EntryStatus {
    POSTED,
    VOIDED,
}

data class LedgerEntry(
    val id: UUID,
    val householdId: UUID,
    val accountId: UUID,
    val categoryId: UUID,
    val actorId: UUID,
    val direction: Direction,
    val originalAmount: Money,
    val amountKzt: Money,
    val accountingStartedAt: Instant,
    val occurredAt: Instant,
    val createdAt: Instant,
    val sourceKey: String,
    val exchangeRate: DailyExchangeRate? = null,
    val status: EntryStatus = EntryStatus.POSTED,
    val version: Long = 0,
    val modifiedAt: Instant = createdAt,
    val refundOf: UUID? = null,
) {
    init {
        originalAmount.requirePositive()
        amountKzt.requirePositive()
        require(amountKzt.currency == Currency.KZT) { "Ledger valuation must be KZT" }
        require(occurredAt >= accountingStartedAt && occurredAt <= modifiedAt && modifiedAt >= createdAt) {
            "Outside accounting interval"
        }
        // Prevent database timestamp rounding from moving an operation into the next currency date.
        require(
            listOf(accountingStartedAt, occurredAt, createdAt, modifiedAt).all {
                it ==
                    it.truncatedTo(ChronoUnit.MICROS)
            },
        ) {
            "Accounting timestamps support microsecond precision"
        }
        require(SOURCE_PATTERN.matches(sourceKey)) { "Invalid financial source key" }
        require(refundOf == null || (direction == Direction.EXPENSE_REFUND && refundOf != id))
        require(version >= 0) { "Invalid entry version" }
        when (originalAmount.currency) {
            Currency.KZT -> {
                require(exchangeRate == null && originalAmount == amountKzt) { "Invalid KZT valuation" }
            }

            Currency.RUB -> {
                requireNotNull(exchangeRate) { "Daily RUB/KZT rate is required" }
                require(exchangeRate.date == occurredAt.atZone(ACCOUNTING_ZONE).toLocalDate()) { "Rate date mismatch" }
                require(exchangeRate.convert(originalAmount) == amountKzt) { "Invalid RUB valuation" }
            }
        }
    }

    companion object {
        private val ACCOUNTING_ZONE = ZoneId.of("Asia/Almaty")
        private val SOURCE_PATTERN = Regex("[A-Za-z0-9:_-]{1,128}")
    }
}
