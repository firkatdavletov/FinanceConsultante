package com.firkatdavletov.financeconsultante.mobileapi.application

import com.firkatdavletov.financeconsultante.ledger.application.CategoryManagement
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

@Component
class CloudBudgetBootstrapValidator(
    private val categoryManagement: CategoryManagement,
    private val clock: Clock,
) {
    fun validate(request: CreateCloudBudget) {
        require(request.currency == null || request.currency in BUDGET_CURRENCIES)
        require(request.accountingStartedOn.year >= 1)
        Money.of(request.openingBalance)
        require(request.accountingStartedOn <= LocalDate.now(clock.withZone(ZoneId.of("Asia/Almaty"))))
        val dictionary =
            mapOf(
                "GROCERIES" to "EXPENSE",
                "HEALTH" to "EXPENSE",
                "SALARY" to "INCOME",
                "OTHER" to "EXPENSE",
            )
        require(
            request.categories.size == dictionary.size &&
                request.categories.map { it.dictionaryKey }.toSet() == dictionary.keys,
        )
        require(
            request.categories
                .map { it.id }
                .toSet()
                .size == dictionary.size,
        )
        require(
            request.categories
                .map { it.name.lowercase() }
                .toSet()
                .size == dictionary.size,
        )
        request.categories.forEach {
            require(it.kind == dictionary[it.dictionaryKey] && categoryManagement.validName(it.name))
            require(it.icon.isNotBlank())
        }
    }
}
