package com.firkatdavletov.financeconsultante.recurring.infrastructure

import com.firkatdavletov.financeconsultante.recurring.application.IncomeRuleRepository
import com.firkatdavletov.financeconsultante.recurring.domain.ExpectedIncomeStatus
import com.firkatdavletov.financeconsultante.recurring.domain.IncomeFrequency
import com.firkatdavletov.financeconsultante.recurring.domain.IncomeRule
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.springframework.jdbc.core.SqlParameterValue
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Types
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
@Transactional(propagation = Propagation.MANDATORY)
class JdbcIncomeRuleRepository(
    private val jdbc: JdbcClient,
) : IncomeRuleRepository {
    override fun rules(
        householdId: UUID?,
        forGeneration: Boolean,
    ): List<IncomeRule> =
        jdbc
            .sql(
                "SELECT * FROM income_rule" +
                    (if (householdId == null) " ORDER BY household_id,id" else " WHERE household_id=? ORDER BY id") +
                    (if (forGeneration) " FOR SHARE SKIP LOCKED" else ""),
            ).let { if (householdId == null) it else it.param(householdId) }
            .query { r, _ ->
                IncomeRule(
                    r.getObject("id", UUID::class.java),
                    r.getObject("household_id", UUID::class.java),
                    r.getObject("member_id", UUID::class.java),
                    r.getObject("category_id", UUID::class.java),
                    Money.of(r.getBigDecimal("amount")),
                    r.getObject("due_on", LocalDate::class.java),
                    r.getString("income_name"),
                    IncomeFrequency.valueOf(r.getString("income_frequency")),
                    r.getBoolean("income_active"),
                    r.getLong("version"),
                    r.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                    r.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
                    r.getObject("effective_on", LocalDate::class.java),
                    ExpectedIncomeStatus.valueOf(r.getString("status")),
                )
            }.list()

    override fun save(rule: IncomeRule) {
        jdbc
            .sql(
                """
                INSERT INTO income_rule(id,household_id,member_id,category_id,amount,due_on,income_name,income_frequency,
                    income_active,version,created_at,updated_at,effective_on,status)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (id) DO UPDATE SET category_id=EXCLUDED.category_id,amount=EXCLUDED.amount,
                    due_on=EXCLUDED.due_on,income_name=EXCLUDED.income_name,income_frequency=EXCLUDED.income_frequency,
                    income_active=EXCLUDED.income_active,version=EXCLUDED.version,updated_at=EXCLUDED.updated_at,
                    effective_on=EXCLUDED.effective_on,status=EXCLUDED.status
                WHERE income_rule.household_id=EXCLUDED.household_id AND income_rule.member_id=EXCLUDED.member_id
                """.trimIndent(),
            ).params(
                rule.id,
                rule.householdId,
                rule.memberId,
                rule.categoryId,
                rule.amount.amount,
                rule.dueOn,
                rule.incomeName,
                rule.incomeFrequency.name,
                rule.incomeActive,
                rule.version,
                rule.createdAt.atOffset(ZoneOffset.UTC),
                rule.updatedAt.atOffset(ZoneOffset.UTC),
                rule.effectiveOn,
                rule.status.name,
            ).update()
            .also { check(it == 1) }
    }

    override fun attach(
        householdId: UUID,
        id: UUID,
    ) {
        jdbc
            .sql(
                """
                UPDATE expected_income SET rule_id=id,scheduled_on=due_on
                WHERE household_id=? AND id=? AND rule_id IS NULL
                """.trimIndent(),
            ).params(householdId, id)
            .update()
    }

    override fun reconcile(
        rule: IncomeRule,
        through: LocalDate,
    ) {
        val occurrences =
            jdbc
                .sql(
                    """
                    SELECT id,scheduled_on,status FROM expected_income e
                    WHERE household_id=? AND rule_id=? AND due_on>=? AND (status='SCHEDULED' OR schedule_cancelled)
                        AND NOT EXISTS (SELECT 1 FROM operation_plan_links p
                            WHERE p.household_id=e.household_id AND p.expected_income_id=e.id)
                    FOR UPDATE
                    """.trimIndent(),
                ).params(rule.householdId, rule.id, rule.effectiveOn)
                .query {
                    r,
                    _,
                    ->
                    Triple(
                        r.getObject("id", UUID::class.java),
                        r.getObject("scheduled_on", LocalDate::class.java),
                        r.getString("status"),
                    )
                }.list()
        val lastDate =
            jdbc
                .sql("SELECT MAX(scheduled_on) FROM expected_income WHERE household_id=? AND rule_id=?")
                .params(rule.householdId, rule.id)
                .query(LocalDate::class.java)
                .optional()
                .orElse(through)
        val horizon = maxOf(through, lastDate)
        val dates = rule.dueDatesThrough(horizon).toSet()
        occurrences.forEach { (id, date, status) -> replaceUnpaidOccurrence(rule, id, date in dates, status) }
        materialize(rule, horizon)
    }

    private fun replaceUnpaidOccurrence(
        rule: IncomeRule,
        id: UUID,
        retained: Boolean,
        status: String,
    ) {
        if (retained) {
            jdbc
                .sql(
                    """
                    UPDATE expected_income SET category_id=?,planned_amount=?,income_name=?,income_frequency=?,
                        income_active=?,status='SCHEDULED',schedule_cancelled=FALSE,version=version+1,updated_at=? WHERE id=?
                    """.trimIndent(),
                ).params(
                    rule.categoryId,
                    rule.amount.amount,
                    rule.incomeName,
                    rule.incomeFrequency.name,
                    rule.incomeActive,
                    rule.updatedAt.atOffset(ZoneOffset.UTC),
                    id,
                ).update()
        } else if (status == "SCHEDULED") {
            jdbc
                .sql(
                    """
                    UPDATE expected_income SET status='CANCELLED',schedule_cancelled=TRUE,
                        version=version+1,updated_at=? WHERE id=?
                    """.trimIndent(),
                ).params(rule.updatedAt.atOffset(ZoneOffset.UTC), id)
                .update()
        }
    }

    override fun materialize(
        rule: IncomeRule,
        through: LocalDate,
    ) {
        rule.dueDatesThrough(through).forEach { date ->
            jdbc
                .sql(
                    """
                    INSERT INTO expected_income(id,household_id,member_id,category_id,planned_amount,due_on,rule_id,
                        scheduled_on,income_name,income_frequency,income_active,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (rule_id,scheduled_on) DO NOTHING
                    """.trimIndent(),
                ).params(
                    UUID.randomUUID(),
                    rule.householdId,
                    rule.memberId,
                    rule.categoryId,
                    rule.amount.amount,
                    date,
                    rule.id,
                    date,
                    rule.incomeName,
                    rule.incomeFrequency.name,
                    rule.incomeActive,
                    rule.updatedAt.atOffset(ZoneOffset.UTC),
                    rule.updatedAt.atOffset(ZoneOffset.UTC),
                ).update()
        }
    }

    override fun metadata(
        householdId: UUID,
        id: UUID,
        name: String?,
        frequency: String?,
        active: Boolean?,
    ) {
        jdbc
            .sql(
                """
                UPDATE expected_income SET income_name=COALESCE(?,income_name),income_frequency=COALESCE(?,income_frequency),
                    income_active=COALESCE(?,income_active) WHERE household_id=? AND id=?
                """.trimIndent(),
            ).params(
                SqlParameterValue(Types.VARCHAR, name),
                SqlParameterValue(Types.VARCHAR, frequency),
                SqlParameterValue(Types.BOOLEAN, active),
                householdId,
                id,
            ).update()
    }
}
