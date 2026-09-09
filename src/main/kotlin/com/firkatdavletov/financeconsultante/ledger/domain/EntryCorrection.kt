package com.firkatdavletov.financeconsultante.ledger.domain

import com.firkatdavletov.financeconsultante.shared.domain.Money
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

sealed interface EntryChange {
    data class Amount(
        val value: Money,
    ) : EntryChange

    data class Category(
        val id: UUID,
    ) : EntryChange

    data class Date(
        val value: LocalDate,
    ) : EntryChange

    data object Void : EntryChange
}

/** Refund limits use the original currency; exchange rate changes must not change the refundable amount. */
object RefundPolicy {
    fun rejection(
        expense: LedgerEntry,
        refunds: List<LedgerEntry>,
        candidate: LedgerEntry,
    ): String? =
        when {
            expense.direction != Direction.EXPENSE || expense.status != EntryStatus.POSTED -> {
                "Исходный расход недоступен или отменён."
            }

            candidate.originalAmount.currency != expense.originalAmount.currency -> {
                "Возврат должен быть в валюте исходного расхода."
            }

            candidate.categoryId != expense.categoryId -> {
                "Возврат должен сохранять категорию исходного расхода."
            }

            candidate.occurredAt < expense.occurredAt -> {
                "Дата возврата раньше расхода."
            }

            refunds
                .filter { it.status == EntryStatus.POSTED && it.id != candidate.id }
                .fold(candidate.originalAmount.amount) { sum, entry -> sum + entry.originalAmount.amount } >
                expense.originalAmount.amount -> {
                "Общая сумма возвратов превышает расход."
            }

            else -> {
                null
            }
        }
}

fun LedgerEntry.revised(now: Instant): LedgerEntry = copy(version = version + 1, modifiedAt = now)
