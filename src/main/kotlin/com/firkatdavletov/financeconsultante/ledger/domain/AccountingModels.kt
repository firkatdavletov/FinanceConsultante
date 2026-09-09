package com.firkatdavletov.financeconsultante.ledger.domain

import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

data class Household(
    val id: UUID,
    val baseCurrency: Currency = Currency.KZT,
    val timeZone: ZoneId = ZoneId.of("Asia/Almaty"),
    val reportTime: LocalTime = LocalTime.parse("08:00"),
) {
    init {
        require(baseCurrency == Currency.KZT) { "Base currency must be KZT" }
        require(timeZone == ZoneId.of("Asia/Almaty")) { "Unsupported accounting time zone" }
        require(reportTime.second == 0 && reportTime.nano == 0) { "Report time must use whole minutes" }
    }
}

data class Member(
    val id: UUID,
    val householdId: UUID,
    val telegramUserId: Long,
) {
    init {
        require(telegramUserId > 0) { "Invalid Telegram user ID" }
    }
}

data class Account(
    val id: UUID,
    val householdId: UUID,
    val openingBalance: Money,
    val accountingStartedAt: Instant,
) {
    init {
        require(openingBalance.currency == Currency.KZT) { "Opening balance must be in KZT" }
        require(accountingStartedAt == accountingStartedAt.truncatedTo(ChronoUnit.MICROS)) {
            "Accounting timestamps support microsecond precision"
        }
    }
}

enum class CategoryKind {
    INCOME,
    EXPENSE,
}

data class Category(
    val id: UUID,
    val householdId: UUID,
    val name: String,
    val kind: CategoryKind,
    val archived: Boolean = false,
    val dictionaryKey: String? = null,
) {
    init {
        require(name.isNotBlank() && name.length <= MAX_NAME_LENGTH) { "Invalid category name" }
    }

    companion object {
        private const val MAX_NAME_LENGTH = 100
    }
}

data class BudgetSetup(
    val household: Household,
    val owner: Member,
    val account: Account,
    val categories: List<Category>,
) {
    init {
        require(owner.householdId == household.id && account.householdId == household.id) { "Budget mismatch" }
        require(categories.all { it.householdId == household.id }) { "Category budget mismatch" }
    }
}
