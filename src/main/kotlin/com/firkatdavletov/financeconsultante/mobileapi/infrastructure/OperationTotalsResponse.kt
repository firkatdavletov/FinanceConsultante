package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import java.math.BigDecimal

data class OperationTotalsResponse(
    val expenses: ApiMoneyResponse,
    val incomes: ApiMoneyResponse,
) {
    companion object {
        fun from(
            expenses: BigDecimal,
            incomes: BigDecimal,
        ): OperationTotalsResponse =
            OperationTotalsResponse(
                ApiMoneyResponse(expenses.setScale(2).toPlainString(), "KZT"),
                ApiMoneyResponse(incomes.setScale(2).toPlainString(), "KZT"),
            )
    }
}
