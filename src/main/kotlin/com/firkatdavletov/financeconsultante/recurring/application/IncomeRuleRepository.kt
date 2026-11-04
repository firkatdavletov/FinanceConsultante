package com.firkatdavletov.financeconsultante.recurring.application

import com.firkatdavletov.financeconsultante.recurring.domain.IncomeRule
import java.time.LocalDate
import java.util.UUID

interface IncomeRuleRepository {
    fun rules(
        householdId: UUID? = null,
        forGeneration: Boolean = false,
    ): List<IncomeRule>

    fun save(rule: IncomeRule)

    /** Attach the legacy occurrence without replacing its financial definition or links. */
    fun attach(
        householdId: UUID,
        id: UUID,
    )

    fun reconcile(
        rule: IncomeRule,
        through: LocalDate,
    )

    fun materialize(
        rule: IncomeRule,
        through: LocalDate,
    )

    fun metadata(
        householdId: UUID,
        id: UUID,
        name: String?,
        frequency: String?,
        active: Boolean?,
    )
}
