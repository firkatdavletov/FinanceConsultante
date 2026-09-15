package com.firkatdavletov.financeconsultante.ledger.infrastructure

import com.firkatdavletov.financeconsultante.ledger.domain.DailyExchangeRate
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.ledger.domain.EntryStatus
import com.firkatdavletov.financeconsultante.ledger.domain.LedgerEntry
import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.springframework.jdbc.core.RowMapper
import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

internal object LedgerRowMapper : RowMapper<LedgerEntry> {
    override fun mapRow(
        row: ResultSet,
        rowNum: Int,
    ): LedgerEntry =
        LedgerEntry(
            id = row.getObject("id", UUID::class.java),
            householdId = row.getObject("household_id", UUID::class.java),
            accountId = row.getObject("account_id", UUID::class.java),
            categoryId = row.getObject("category_id", UUID::class.java),
            actorId = row.getObject("actor_id", UUID::class.java),
            direction = Direction.valueOf(row.getString("direction")),
            originalAmount =
                Money.of(
                    row.getBigDecimal("original_amount"),
                    Currency.valueOf(row.getString("original_currency")),
                ),
            amountKzt = Money.of(row.getBigDecimal("amount_kzt")),
            accountingStartedAt = row.getObject("accounting_started_at", OffsetDateTime::class.java).toInstant(),
            occurredAt = row.getObject("occurred_at", OffsetDateTime::class.java).toInstant(),
            createdAt = row.getObject("created_at", OffsetDateTime::class.java).toInstant(),
            sourceKey = row.getString("source_key"),
            exchangeRate =
                row.getBigDecimal("rub_kzt")?.let { rate ->
                    DailyExchangeRate(
                        row.getObject("occurred_on", LocalDate::class.java),
                        rate,
                        row.getString("rate_source"),
                        row.getObject("rate_fetched_at", OffsetDateTime::class.java).toInstant(),
                        row.getObject("rate_quoted_at", OffsetDateTime::class.java).toInstant(),
                    )
                },
            status = EntryStatus.valueOf(row.getString("status")),
            version = row.getLong("version"),
            modifiedAt = row.getObject("modified_at", OffsetDateTime::class.java).toInstant(),
            refundOf = row.getObject("refund_of", UUID::class.java),
            normalizedDescription = row.getString("normalized_description"),
        )
}
