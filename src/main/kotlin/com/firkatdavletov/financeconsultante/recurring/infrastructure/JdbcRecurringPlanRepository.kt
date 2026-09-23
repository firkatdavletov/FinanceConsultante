package com.firkatdavletov.financeconsultante.recurring.infrastructure

import com.firkatdavletov.financeconsultante.recurring.application.RecurringPlanRepository
import com.firkatdavletov.financeconsultante.recurring.application.RecurringRuleView
import com.firkatdavletov.financeconsultante.recurring.domain.ExpectedIncome
import com.firkatdavletov.financeconsultante.recurring.domain.ExpectedIncomeStatus
import com.firkatdavletov.financeconsultante.recurring.domain.ExpectedIncomeView
import com.firkatdavletov.financeconsultante.recurring.domain.OccurrenceStatus
import com.firkatdavletov.financeconsultante.recurring.domain.RecurrenceFrequency
import com.firkatdavletov.financeconsultante.recurring.domain.RecurringOccurrence
import com.firkatdavletov.financeconsultante.recurring.domain.RecurringOccurrenceView
import com.firkatdavletov.financeconsultante.recurring.domain.RecurringRule
import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.springframework.jdbc.core.SqlParameterValue
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
@Transactional(propagation = Propagation.MANDATORY)
@Suppress("TooManyFunctions") // The adapter implements one cohesive recurring-plan persistence port.
class JdbcRecurringPlanRepository(
    private val jdbc: JdbcClient,
) : RecurringPlanRepository {
    override fun insertRule(rule: RecurringRule) {
        check(
            jdbc
                .sql(
                    """
                    INSERT INTO recurring_rule(
                        id, household_id, member_id, category_id, frequency, schedule_value, amount, currency,
                        starts_on, active, version, created_at, updated_at, normalized_description
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).params(
                    rule.id,
                    rule.householdId,
                    rule.memberId,
                    rule.categoryId,
                    rule.frequency.name,
                    rule.scheduleValue,
                    rule.amount.amount,
                    rule.amount.currency.name,
                    rule.startsOn,
                    rule.active,
                    rule.version,
                    offset(rule.createdAt),
                    offset(rule.updatedAt),
                    SqlParameterValue(Types.VARCHAR, rule.normalizedDescription),
                ).update() == 1,
        ) { "Recurring rule was not inserted" }
    }

    override fun rules(
        householdId: UUID,
        limit: Int,
    ): List<RecurringRuleView> =
        jdbc
            .sql(
                """
                SELECT r.*, c.name AS category_name FROM recurring_rule r
                JOIN category c ON c.household_id = r.household_id AND c.id = r.category_id
                WHERE r.household_id = ? ORDER BY r.created_at DESC, r.id LIMIT ?
                """.trimIndent(),
            ).params(householdId, limit)
            .query { row, _ -> RecurringRuleView(mapRule(row), row.getString("category_name")) }
            .list()

    override fun activeRules(householdId: UUID?): List<RecurringRule> {
        val statement =
            if (householdId == null) {
                jdbc.sql("SELECT * FROM recurring_rule WHERE active ORDER BY household_id, id")
            } else {
                jdbc
                    .sql("SELECT * FROM recurring_rule WHERE household_id = ? AND active ORDER BY id")
                    .param(householdId)
            }
        return statement.query { row, _ -> mapRule(row) }.list()
    }

    override fun findRule(
        householdId: UUID,
        id: UUID,
        lock: Boolean,
    ): RecurringRule? =
        jdbc
            .sql("SELECT * FROM recurring_rule WHERE household_id = ? AND id = ?" + lock(lock))
            .params(householdId, id)
            .query { row, _ -> mapRule(row) }
            .optional()
            .orElse(null)

    override fun updateRuleAmount(
        rule: RecurringRule,
        previousVersion: Long,
        effectiveOn: LocalDate,
    ): Boolean {
        val updated =
            jdbc
                .sql(
                    """
                    UPDATE recurring_rule SET amount = ?, version = ?, updated_at = ?
                    WHERE household_id = ? AND id = ? AND version = ? AND active
                    """.trimIndent(),
                ).params(
                    rule.amount.amount,
                    rule.version,
                    offset(rule.updatedAt),
                    rule.householdId,
                    rule.id,
                    previousVersion,
                ).update()
        if (updated != 1) return false
        jdbc
            .sql(
                """
                UPDATE recurring_occurrence o SET planned_amount = ?, version = version + 1, updated_at = ?
                WHERE household_id = ? AND rule_id = ? AND due_on >= ? AND status = 'SCHEDULED'
                    AND NOT EXISTS (
                        SELECT 1 FROM recurring_occurrence_payment p
                        JOIN ledger_entry l ON l.household_id = p.household_id AND l.id = p.ledger_entry_id
                        WHERE p.household_id = o.household_id AND p.occurrence_id = o.id AND l.status = 'POSTED'
                    )
                """.trimIndent(),
            ).params(rule.amount.amount, offset(rule.updatedAt), rule.householdId, rule.id, effectiveOn)
            .update()
        return true
    }

    override fun deactivateRule(
        rule: RecurringRule,
        previousVersion: Long,
        effectiveOn: LocalDate,
    ): Boolean {
        val updated =
            jdbc
                .sql(
                    """
                    UPDATE recurring_rule SET active = FALSE, version = ?, updated_at = ?
                    WHERE household_id = ? AND id = ? AND version = ? AND active
                    """.trimIndent(),
                ).params(rule.version, offset(rule.updatedAt), rule.householdId, rule.id, previousVersion)
                .update()
        if (updated != 1) return false
        jdbc
            .sql(
                """
                UPDATE recurring_occurrence SET status = 'CANCELLED', version = version + 1, updated_at = ?
                WHERE household_id = ? AND rule_id = ? AND due_on >= ? AND status = 'SCHEDULED'
                """.trimIndent(),
            ).params(offset(rule.updatedAt), rule.householdId, rule.id, effectiveOn)
            .update()
        return true
    }

    override fun insertOccurrences(
        rule: RecurringRule,
        dueDates: List<LocalDate>,
        now: Instant,
    ): Int =
        dueDates.sumOf { dueOn ->
            jdbc
                .sql(
                    """
                    INSERT INTO recurring_occurrence(
                        id, household_id, rule_id, category_id, planned_amount, currency, due_on, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (rule_id, due_on) DO NOTHING
                    """.trimIndent(),
                ).params(
                    UUID.randomUUID(),
                    rule.householdId,
                    rule.id,
                    rule.categoryId,
                    rule.amount.amount,
                    rule.amount.currency.name,
                    dueOn,
                    offset(now),
                    offset(now),
                ).update()
        }

    override fun occurrences(
        householdId: UUID,
        throughInclusive: LocalDate,
        limit: Int?,
    ): List<RecurringOccurrenceView> {
        val parameters = mutableMapOf<String, Any>("household" to householdId, "through" to throughInclusive)
        if (limit != null) parameters["limit"] = limit
        return jdbc
            .sql(OCCURRENCE_VIEW + if (limit == null) "" else " LIMIT :limit")
            .params(parameters)
            .query { row, _ -> mapOccurrenceView(row) }
            .list()
    }

    override fun matchableOccurrences(
        householdId: UUID,
        categoryId: UUID,
        closestTo: LocalDate,
        throughInclusive: LocalDate,
        limit: Int,
    ): List<RecurringOccurrenceView> =
        jdbc
            .sql(MATCHABLE_OCCURRENCES)
            .params(
                mapOf(
                    "household" to householdId,
                    "category" to categoryId,
                    "closest" to closestTo,
                    "through" to throughInclusive,
                    "limit" to limit,
                ),
            ).query { row, _ -> mapOccurrenceView(row) }
            .list()

    override fun findOccurrence(
        householdId: UUID,
        id: UUID,
        lock: Boolean,
    ): RecurringOccurrence? =
        jdbc
            .sql("SELECT * FROM recurring_occurrence WHERE household_id = ? AND id = ?" + lock(lock))
            .params(householdId, id)
            .query { row, _ -> mapOccurrence(row) }
            .optional()
            .orElse(null)

    override fun occurrenceView(
        householdId: UUID,
        id: UUID,
    ): RecurringOccurrenceView? =
        jdbc
            .sql(OCCURRENCE_BY_ID)
            .params(householdId, id)
            .query { row, _ -> mapOccurrenceView(row) }
            .optional()
            .orElse(null)

    override fun advanceOccurrence(
        occurrenceId: UUID,
        previousVersion: Long,
        now: Instant,
    ): Boolean =
        jdbc
            .sql(
                """
                UPDATE recurring_occurrence SET version = version + 1, updated_at = ?
                WHERE id = ? AND version = ? AND status = 'SCHEDULED'
                """.trimIndent(),
            ).params(offset(now), occurrenceId, previousVersion)
            .update() == 1

    override fun cancelOccurrence(
        occurrenceId: UUID,
        previousVersion: Long,
        now: Instant,
    ): Boolean =
        jdbc
            .sql(
                """
                UPDATE recurring_occurrence SET status = 'CANCELLED', version = version + 1, updated_at = ?
                WHERE id = ? AND version = ? AND status = 'SCHEDULED'
                """.trimIndent(),
            ).params(offset(now), occurrenceId, previousVersion)
            .update() == 1

    override fun linkPayment(
        householdId: UUID,
        occurrenceId: UUID,
        ledgerEntryId: UUID,
        now: Instant,
    ): Boolean =
        jdbc
            .sql(
                """
                INSERT INTO recurring_occurrence_payment(household_id, occurrence_id, ledger_entry_id, created_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """.trimIndent(),
            ).params(householdId, occurrenceId, ledgerEntryId, offset(now))
            .update() == 1

    override fun insertExpectedIncome(expectedIncome: ExpectedIncome) {
        check(
            jdbc
                .sql(
                    """
                    INSERT INTO expected_income(
                        id, household_id, member_id, category_id, planned_amount, currency, due_on,
                        status, version, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).params(
                    expectedIncome.id,
                    expectedIncome.householdId,
                    expectedIncome.memberId,
                    expectedIncome.categoryId,
                    expectedIncome.plannedAmount.amount,
                    expectedIncome.plannedAmount.currency.name,
                    expectedIncome.dueOn,
                    expectedIncome.status.name,
                    expectedIncome.version,
                    offset(expectedIncome.createdAt),
                    offset(expectedIncome.updatedAt),
                ).update() == 1,
        ) { "Expected income was not inserted" }
    }

    override fun expectedIncomes(
        householdId: UUID,
        throughInclusive: LocalDate?,
        limit: Int?,
    ): List<ExpectedIncomeView> {
        val parameters = mutableMapOf<String, Any>("household" to householdId)
        if (throughInclusive != null) parameters["through"] = throughInclusive
        if (limit != null) parameters["limit"] = limit
        val condition = if (throughInclusive == null) "" else " AND e.due_on <= :through"
        return jdbc
            .sql(EXPECTED_VIEW + condition + EXPECTED_ORDER + if (limit == null) "" else " LIMIT :limit")
            .params(parameters)
            .query { row, _ -> mapExpectedView(row) }
            .list()
    }

    override fun findExpectedIncome(
        householdId: UUID,
        id: UUID,
        lock: Boolean,
    ): ExpectedIncome? =
        jdbc
            .sql("SELECT * FROM expected_income WHERE household_id = ? AND id = ?" + lock(lock))
            .params(householdId, id)
            .query { row, _ -> mapExpected(row) }
            .optional()
            .orElse(null)

    override fun expectedIncomeView(
        householdId: UUID,
        id: UUID,
    ): ExpectedIncomeView? =
        jdbc
            .sql(EXPECTED_VIEW + " AND e.id = :id" + EXPECTED_ORDER)
            .params(mapOf("household" to householdId, "id" to id))
            .query { row, _ -> mapExpectedView(row) }
            .optional()
            .orElse(null)

    override fun cancelExpectedIncome(
        expectedIncomeId: UUID,
        previousVersion: Long,
        now: Instant,
    ): Boolean =
        jdbc
            .sql(
                """
                UPDATE expected_income SET status = 'CANCELLED', version = version + 1, updated_at = ?
                WHERE id = ? AND version = ? AND status = 'SCHEDULED'
                """.trimIndent(),
            ).params(offset(now), expectedIncomeId, previousVersion)
            .update() == 1

    override fun advanceExpectedIncome(
        expectedIncomeId: UUID,
        previousVersion: Long,
        now: Instant,
    ): Boolean =
        jdbc
            .sql(
                """
                UPDATE expected_income SET version = version + 1, updated_at = ?
                WHERE id = ? AND version = ? AND status = 'SCHEDULED'
                """.trimIndent(),
            ).params(offset(now), expectedIncomeId, previousVersion)
            .update() == 1

    override fun linkExpectedIncome(
        householdId: UUID,
        expectedIncomeId: UUID,
        ledgerEntryId: UUID,
        now: Instant,
    ) {
        check(
            jdbc
                .sql(
                    """
                    INSERT INTO expected_income_match(household_id, expected_income_id, ledger_entry_id, created_at)
                    VALUES (?, ?, ?, ?)
                    """.trimIndent(),
                ).params(householdId, expectedIncomeId, ledgerEntryId, offset(now))
                .update() == 1,
        ) { "Expected income was not linked" }
    }

    override fun ledgerEntryAlreadyPlanned(
        householdId: UUID,
        ledgerEntryId: UUID,
    ): Boolean =
        jdbc
            .sql(
                """
                SELECT EXISTS (
                    SELECT 1 FROM recurring_occurrence_payment WHERE household_id = ? AND ledger_entry_id = ?
                    UNION ALL
                    SELECT 1 FROM expected_income_match WHERE household_id = ? AND ledger_entry_id = ?
                )
                """.trimIndent(),
            ).params(householdId, ledgerEntryId, householdId, ledgerEntryId)
            .query(Boolean::class.java)
            .single()

    private fun mapRule(row: java.sql.ResultSet): RecurringRule =
        RecurringRule(
            row.getObject("id", UUID::class.java),
            row.getObject("household_id", UUID::class.java),
            row.getObject("member_id", UUID::class.java),
            row.getObject("category_id", UUID::class.java),
            RecurrenceFrequency.valueOf(row.getString("frequency")),
            row.getInt("schedule_value"),
            Money.of(row.getBigDecimal("amount"), Currency.valueOf(row.getString("currency"))),
            row.getObject("starts_on", LocalDate::class.java),
            row.getBoolean("active"),
            row.getLong("version"),
            instant(row, "created_at"),
            instant(row, "updated_at"),
            row.getString("normalized_description"),
        )

    private fun mapOccurrence(row: java.sql.ResultSet): RecurringOccurrence =
        RecurringOccurrence(
            row.getObject("id", UUID::class.java),
            row.getObject("household_id", UUID::class.java),
            row.getObject("rule_id", UUID::class.java),
            row.getObject("category_id", UUID::class.java),
            Money.of(row.getBigDecimal("planned_amount"), Currency.valueOf(row.getString("currency"))),
            row.getObject("due_on", LocalDate::class.java),
            OccurrenceStatus.valueOf(row.getString("status")),
            row.getLong("version"),
            instant(row, "created_at"),
            instant(row, "updated_at"),
        )

    private fun mapOccurrenceView(row: java.sql.ResultSet): RecurringOccurrenceView =
        RecurringOccurrenceView(
            mapOccurrence(row),
            row.getString("category_name"),
            Money.of(row.getBigDecimal("paid_amount"), Currency.KZT),
        )

    private fun mapExpected(row: java.sql.ResultSet): ExpectedIncome =
        ExpectedIncome(
            row.getObject("id", UUID::class.java),
            row.getObject("household_id", UUID::class.java),
            row.getObject("member_id", UUID::class.java),
            row.getObject("category_id", UUID::class.java),
            Money.of(row.getBigDecimal("planned_amount"), Currency.valueOf(row.getString("currency"))),
            row.getObject("due_on", LocalDate::class.java),
            ExpectedIncomeStatus.valueOf(row.getString("status")),
            row.getLong("version"),
            instant(row, "created_at"),
            instant(row, "updated_at"),
        )

    private fun mapExpectedView(row: java.sql.ResultSet): ExpectedIncomeView =
        ExpectedIncomeView(
            mapExpected(row),
            row.getString("category_name"),
            Money.of(row.getBigDecimal("received_amount"), Currency.KZT),
            row.getInt("posted_matches"),
        )

    private fun instant(
        row: java.sql.ResultSet,
        column: String,
    ): Instant = row.getObject(column, OffsetDateTime::class.java).toInstant()

    private fun offset(instant: Instant) = instant.atOffset(ZoneOffset.UTC)

    private fun lock(enabled: Boolean) = if (enabled) " FOR UPDATE" else ""

    companion object {
        private val OCCURRENCE_VIEW =
            """
            SELECT o.*, c.name AS category_name,
                COALESCE(SUM(l.original_amount) FILTER (WHERE l.status = 'POSTED'), 0) AS paid_amount
            FROM recurring_occurrence o
            JOIN category c ON c.household_id = o.household_id AND c.id = o.category_id
            LEFT JOIN recurring_occurrence_payment p
                ON p.household_id = o.household_id AND p.occurrence_id = o.id
            LEFT JOIN ledger_entry l ON l.household_id = p.household_id AND l.id = p.ledger_entry_id
            WHERE o.household_id = :household AND o.due_on <= :through
            GROUP BY o.id, c.name ORDER BY o.due_on, o.id
            """.trimIndent()
        private val OCCURRENCE_BY_ID =
            """
            SELECT o.*, c.name AS category_name,
                COALESCE(SUM(l.original_amount) FILTER (WHERE l.status = 'POSTED'), 0) AS paid_amount
            FROM recurring_occurrence o
            JOIN category c ON c.household_id = o.household_id AND c.id = o.category_id
            LEFT JOIN recurring_occurrence_payment p
                ON p.household_id = o.household_id AND p.occurrence_id = o.id
            LEFT JOIN ledger_entry l ON l.household_id = p.household_id AND l.id = p.ledger_entry_id
            WHERE o.household_id = ? AND o.id = ? GROUP BY o.id, c.name
            """.trimIndent()
        private val MATCHABLE_OCCURRENCES =
            """
            SELECT o.*, c.name AS category_name,
                COALESCE(SUM(l.original_amount) FILTER (WHERE l.status = 'POSTED'), 0) AS paid_amount
            FROM recurring_occurrence o
            JOIN category c ON c.household_id = o.household_id AND c.id = o.category_id
            LEFT JOIN recurring_occurrence_payment p
                ON p.household_id = o.household_id AND p.occurrence_id = o.id
            LEFT JOIN ledger_entry l ON l.household_id = p.household_id AND l.id = p.ledger_entry_id
            WHERE o.household_id = :household AND o.category_id = :category
                AND o.status = 'SCHEDULED' AND o.due_on <= :through
            GROUP BY o.id, c.name
            HAVING COALESCE(SUM(l.original_amount) FILTER (WHERE l.status = 'POSTED'), 0) < o.planned_amount
            ORDER BY ABS(o.due_on - :closest), o.due_on, o.id
            LIMIT :limit
            """.trimIndent()
        private val EXPECTED_VIEW =
            """
            SELECT e.*, c.name AS category_name,
                COALESCE(SUM(l.original_amount) FILTER (WHERE l.status = 'POSTED'), 0) AS received_amount,
                COUNT(l.id) FILTER (WHERE l.status = 'POSTED') AS posted_matches
            FROM expected_income e
            JOIN category c ON c.household_id = e.household_id AND c.id = e.category_id
            LEFT JOIN expected_income_match m
                ON m.household_id = e.household_id AND m.expected_income_id = e.id
            LEFT JOIN ledger_entry l ON l.household_id = m.household_id AND l.id = m.ledger_entry_id
            WHERE e.household_id = :household
            """.trimIndent()
        private const val EXPECTED_ORDER = " GROUP BY e.id, c.name ORDER BY e.due_on, e.id"
    }
}
