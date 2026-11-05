package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import com.firkatdavletov.financeconsultante.mobileapi.application.CreateCloudBudget
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.ZoneId
import java.util.UUID

/** Called only inside the creation transaction, after application validation. */
@Repository
class JdbcCloudBudgetBootstrap(
    private val jdbc: JdbcClient,
) {
    fun create(
        request: CreateCloudBudget,
        name: String = "Мой бюджет",
    ) {
        jdbc
            .sql("INSERT INTO household(id, budget_name, budget_currency) VALUES (?, ?, ?)")
            .params(request.householdId, name, request.currency ?: "KZT")
            .update()
        jdbc
            .sql(
                """
INSERT INTO member(id, household_id, telegram_user_id, reports_enabled) VALUES (?, ?, NULL,
FALSE)
                """.trimIndent(),
            ).params(request.memberId, request.householdId)
            .update()
        jdbc
            .sql(
                """
INSERT INTO account(id, household_id, opening_balance, accounting_started_at) VALUES (?, ?, ?,
?)
                """.trimIndent(),
            ).params(
                UUID.randomUUID(),
                request.householdId,
                request.openingBalance.toBigDecimal(),
                request.accountingStartedOn.atStartOfDay(ZoneId.of("Asia/Almaty")).toOffsetDateTime(),
            ).update()
        request.categories.forEach { c ->
            jdbc
                .sql(
                    """
INSERT INTO category(id, household_id, name, kind, icon_code, dictionary_key) VALUES (?, ?, ?,
?, ?, ?)
                    """.trimIndent(),
                ).params(c.id, request.householdId, c.name, c.kind, c.icon, c.dictionaryKey)
                .update()
        }
    }
}
