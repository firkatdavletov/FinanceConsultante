package com.firkatdavletov.financeconsultante.telegram.domain

import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

val ACCOUNTING_ZONE: ZoneId = ZoneId.of("Asia/Almaty")

data class BudgetSettings(
    val openingBalance: Money,
    val startedOn: LocalDate,
    val reportTime: LocalTime,
    val cashReserve: Money,
) {
    init {
        require(openingBalance.currency == Currency.KZT && cashReserve.currency == Currency.KZT)
        require(cashReserve.amount.signum() >= 0)
        require(reportTime.second == 0 && reportTime.nano == 0)
    }
}

enum class SetupStep { IDLE, OPENING, DATE, REPORT_TIME, RESERVE }

data class SetupDialog(
    val step: SetupStep = SetupStep.IDLE,
    val initialSetup: Boolean = false,
    val openingBalance: Money? = null,
    val startedOn: LocalDate? = null,
    val reportTime: LocalTime = LocalTime.parse("08:00"),
    val cashReserve: Money = Money.of("0"),
) {
    fun settings(): BudgetSettings =
        BudgetSettings(checkNotNull(openingBalance), checkNotNull(startedOn), reportTime, cashReserve)
}

data class SetupDecision(
    val dialog: SetupDialog,
    val reply: String,
    val save: BudgetSettings? = null,
)
