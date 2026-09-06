package com.firkatdavletov.financeconsultante.ledger.infrastructure

import com.firkatdavletov.financeconsultante.ledger.application.BudgetRepository
import com.firkatdavletov.financeconsultante.ledger.domain.Account
import com.firkatdavletov.financeconsultante.ledger.domain.BudgetSetup
import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JdbcBudgetRepository(
    private val jdbc: JdbcClient,
) : BudgetRepository {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun create(setup: BudgetSetup) {
        jdbc
            .sql("INSERT INTO household (id, base_currency, time_zone, report_time) VALUES (?, ?, ?, ?)")
            .params(
                setup.household.id,
                setup.household.baseCurrency.name,
                setup.household.timeZone.id,
                setup.household.reportTime,
            ).update()
        jdbc
            .sql("INSERT INTO member (id, household_id, telegram_user_id) VALUES (?, ?, ?)")
            .params(setup.owner.id, setup.household.id, setup.owner.telegramUserId)
            .update()
        jdbc
            .sql(
                "INSERT INTO account (id, household_id, opening_balance, accounting_started_at) VALUES (?, ?, ?, ?)",
            ).params(
                setup.account.id,
                setup.household.id,
                setup.account.openingBalance.amount,
                setup.account.accountingStartedAt.atOffset(ZoneOffset.UTC),
            ).update()
        setup.categories.forEach { category ->
            jdbc
                .sql("INSERT INTO category (id, household_id, name, kind, archived) VALUES (?, ?, ?, ?, ?)")
                .params(
                    category.id,
                    category.householdId,
                    category.name,
                    category.kind.name,
                    category.archived,
                ).update()
        }
    }

    override fun findOwnedAccount(
        householdId: UUID,
        memberId: UUID,
    ): Account? =
        jdbc
            .sql(
                """
                SELECT a.* FROM account a JOIN member m ON m.household_id = a.household_id
                WHERE a.household_id = :household AND m.id = :member AND m.role = 'OWNER'
                """.trimIndent(),
            ).param("household", householdId)
            .param("member", memberId)
            .query { row, _ ->
                Account(
                    row.getObject("id", UUID::class.java),
                    row.getObject("household_id", UUID::class.java),
                    Money.of(row.getBigDecimal("opening_balance")),
                    row.getObject("accounting_started_at", OffsetDateTime::class.java).toInstant(),
                )
            }.optional()
            .orElse(null)

    override fun findCategory(
        householdId: UUID,
        categoryId: UUID,
    ): Category? =
        jdbc
            .sql(
                "SELECT * FROM category WHERE household_id = ? AND id = ?",
            ).params(householdId, categoryId)
            .query { row, _ ->
                Category(
                    row.getObject("id", UUID::class.java),
                    row.getObject("household_id", UUID::class.java),
                    row.getString("name"),
                    CategoryKind.valueOf(row.getString("kind")),
                    row.getBoolean("archived"),
                )
            }.optional()
            .orElse(null)
}
