package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import com.firkatdavletov.financeconsultante.mobileapi.application.OperationPlanRepository
import com.firkatdavletov.financeconsultante.mobileapi.application.PlanLinkTarget
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

@Repository
class JdbcOperationPlanRepository(
    private val jdbc: JdbcClient,
) : OperationPlanRepository {
    override fun enabled(household: UUID): Boolean =
        jdbc
            .sql("SELECT confirmed_plan_linking FROM household WHERE id=? FOR SHARE")
            .param(household)
            .query(Boolean::class.java)
            .single()

    override fun activate(household: UUID) {
        jdbc
            .sql(
                "UPDATE household SET confirmed_plan_linking=TRUE WHERE id=? AND NOT confirmed_plan_linking",
            ).param(household)
            .update()
    }

    override fun linked(
        household: UUID,
        ledger: UUID,
    ): PlanLinkTarget? =
        jdbc
            .sql(
                """
                SELECT p.*,COALESCE(o.due_on,i.due_on) AS due_on,COALESCE(o.rule_id,i.rule_id) AS rule_id
                FROM operation_plan_links p
                LEFT JOIN recurring_occurrence o ON o.household_id=p.household_id AND o.id=p.occurrence_id
                LEFT JOIN expected_income i ON i.household_id=p.household_id AND i.id=p.expected_income_id
                WHERE p.household_id=? AND p.ledger_id=?
                """.trimIndent(),
            ).params(household, ledger)
            .query { r, _ ->
                PlanLinkTarget(
                    r.getString("direction"),
                    r.getObject("occurrence_id", UUID::class.java)
                        ?: r.getObject("expected_income_id", UUID::class.java),
                    r.getObject("rule_id", UUID::class.java),
                    r.getObject("due_on", LocalDate::class.java),
                )
            }.optional()
            .orElse(null)

    override fun replace(
        household: UUID,
        ledger: UUID,
        target: PlanLinkTarget?,
    ) {
        jdbc
            .sql(
                "DELETE FROM operation_plan_links WHERE household_id=? AND ledger_id=?",
            ).params(household, ledger)
            .update()
        if (target != null) {
            jdbc
                .sql(
                    """
                    INSERT INTO operation_plan_links(household_id,ledger_id,occurrence_id,expected_income_id,created_at)
                    VALUES (:h,:l,:o,:i,CURRENT_TIMESTAMP)
                    """.trimIndent(),
                ).param("h", household)
                .param("l", ledger)
                .param("o", target.planId.takeIf { target.type == "EXPENSE" }, java.sql.Types.OTHER)
                .param("i", target.planId.takeIf { target.type == "INCOME" }, java.sql.Types.OTHER)
                .update()
        }
    }
}
