package com.firkatdavletov.financeconsultante.recurring.infrastructure

import com.firkatdavletov.financeconsultante.recurring.application.RecurringCandidateView
import com.firkatdavletov.financeconsultante.recurring.application.RecurringExpenseAnalysisRepository
import com.firkatdavletov.financeconsultante.recurring.domain.ExpenseObservation
import com.firkatdavletov.financeconsultante.recurring.domain.RecurrenceFrequency
import com.firkatdavletov.financeconsultante.recurring.domain.RecurringCandidateStatus
import com.firkatdavletov.financeconsultante.recurring.domain.RecurringExpenseCandidate
import com.firkatdavletov.financeconsultante.recurring.domain.RulePaymentObservation
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
@Transactional(propagation = Propagation.MANDATORY)
@Suppress("TooManyFunctions") // One repository owns the candidate aggregate and its evidence.
class JdbcRecurringExpenseAnalysisRepository(
    private val jdbc: JdbcClient,
) : RecurringExpenseAnalysisRepository {
    override fun observations(
        householdId: UUID,
        startInclusive: LocalDate,
        endExclusive: LocalDate,
    ): List<ExpenseObservation> =
        jdbc
            .sql(OBSERVATIONS)
            .param("household", householdId)
            .param("start", startInclusive)
            .param("end", endExclusive)
            .query { row, _ ->
                ExpenseObservation(
                    row.getObject("id", UUID::class.java),
                    row.getObject("category_id", UUID::class.java),
                    row.getString("normalized_description"),
                    row.getObject("occurred_on", LocalDate::class.java),
                    Money.of(row.getBigDecimal("amount_kzt")),
                )
            }.list()

    override fun rulePayments(
        householdId: UUID,
        startInclusive: LocalDate,
        endExclusive: LocalDate,
    ): List<RulePaymentObservation> =
        jdbc
            .sql(RULE_PAYMENTS)
            .param("household", householdId)
            .param("start", startInclusive)
            .param("end", endExclusive)
            .query { row, _ ->
                RulePaymentObservation(
                    row.getObject("rule_id", UUID::class.java),
                    row.getObject("occurrence_id", UUID::class.java),
                    row.getObject("due_on", LocalDate::class.java),
                    Money.of(row.getBigDecimal("paid_amount")),
                )
            }.list()

    override fun saveCandidate(
        candidate: RecurringExpenseCandidate,
        evidence: List<ExpenseObservation>,
        now: Instant,
    ): Boolean {
        val storedId =
            jdbc
                .sql(
                    """
                    INSERT INTO recurring_expense_candidate AS existing(
                        id, household_id, member_id, category_id, normalized_description, frequency,
                        schedule_value, suggested_amount, currency, status, version, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (household_id, normalized_description, category_id, frequency) DO UPDATE SET
                        member_id = EXCLUDED.member_id,
                        schedule_value = EXCLUDED.schedule_value,
                        suggested_amount = EXCLUDED.suggested_amount,
                        version = existing.version + 1,
                        updated_at = EXCLUDED.updated_at
                    WHERE existing.status = 'PENDING'
                    RETURNING id
                    """.trimIndent(),
                ).params(
                    candidate.id,
                    candidate.householdId,
                    candidate.memberId,
                    candidate.categoryId,
                    candidate.normalizedDescription,
                    candidate.frequency.name,
                    candidate.scheduleValue,
                    candidate.suggestedAmount.amount,
                    candidate.suggestedAmount.currency.name,
                    candidate.status.name,
                    candidate.version,
                    offset(candidate.createdAt),
                    offset(candidate.updatedAt),
                ).query(UUID::class.java)
                .optional()
                .orElse(null) ?: return false
        jdbc
            .sql(
                "DELETE FROM recurring_expense_candidate_evidence " +
                    "WHERE household_id = ? AND candidate_id = ?",
            ).params(candidate.householdId, storedId)
            .update()
        val evidenceInserted =
            evidence.sumOf { item ->
                jdbc
                    .sql(
                        """
                        INSERT INTO recurring_expense_candidate_evidence(
                            household_id, candidate_id, ledger_entry_id, created_at
                        ) VALUES (?, ?, ?, ?)
                        """.trimIndent(),
                    ).params(candidate.householdId, storedId, item.entryId, offset(now))
                    .update()
            }
        check(evidenceInserted == evidence.size) { "Recurring candidate evidence was not inserted" }
        return true
    }

    override fun pendingCandidates(
        householdId: UUID,
        limit: Int,
    ): List<RecurringCandidateView> =
        jdbc
            .sql(CANDIDATES + " AND x.status = 'PENDING' ORDER BY x.updated_at DESC, x.id LIMIT :limit")
            .param("household", householdId)
            .param("limit", limit)
            .query { row, _ -> candidateRow(row) }
            .list()
            .map { candidateView(it, activeOnly = true) }

    override fun findCandidate(
        householdId: UUID,
        candidateId: UUID,
        lock: Boolean,
    ): RecurringCandidateView? =
        jdbc
            .sql(CANDIDATES + " AND x.id = :id" + if (lock) " FOR UPDATE OF x" else "")
            .param("household", householdId)
            .param("id", candidateId)
            .query { row, _ -> candidateRow(row) }
            .optional()
            .orElse(null)
            ?.let { candidateView(it, activeOnly = true) }

    override fun activeEvidence(
        householdId: UUID,
        candidateId: UUID,
    ): List<ExpenseObservation> = evidence(householdId, candidateId, activeOnly = true, lock = true)

    override fun accept(
        candidate: RecurringExpenseCandidate,
        ruleId: UUID,
        now: Instant,
    ): Boolean =
        jdbc
            .sql(
                """
                UPDATE recurring_expense_candidate
                SET status = 'ACCEPTED', accepted_rule_id = :rule, version = version + 1, updated_at = :now
                WHERE household_id = :household AND id = :id AND version = :version AND status = 'PENDING'
                """.trimIndent(),
            ).param("rule", ruleId)
            .param("now", offset(now))
            .param("household", candidate.householdId)
            .param("id", candidate.id)
            .param("version", candidate.version)
            .update() == 1

    override fun reject(
        candidate: RecurringExpenseCandidate,
        now: Instant,
    ): Boolean =
        jdbc
            .sql(
                """
                UPDATE recurring_expense_candidate
                SET status = 'REJECTED', version = version + 1, updated_at = :now
                WHERE household_id = :household AND id = :id AND version = :version AND status = 'PENDING'
                """.trimIndent(),
            ).param("now", offset(now))
            .param("household", candidate.householdId)
            .param("id", candidate.id)
            .param("version", candidate.version)
            .update() == 1

    private fun candidateRow(row: java.sql.ResultSet): CandidateRow =
        CandidateRow(
            RecurringExpenseCandidate(
                row.getObject("id", UUID::class.java),
                row.getObject("household_id", UUID::class.java),
                row.getObject("member_id", UUID::class.java),
                row.getObject("category_id", UUID::class.java),
                row.getString("normalized_description"),
                RecurrenceFrequency.valueOf(row.getString("frequency")),
                row.getInt("schedule_value"),
                Money.of(row.getBigDecimal("suggested_amount")),
                RecurringCandidateStatus.valueOf(row.getString("status")),
                row.getObject("accepted_rule_id", UUID::class.java),
                row.getLong("version"),
                instant(row, "created_at"),
                instant(row, "updated_at"),
            ),
            row.getString("category_name"),
        )

    private fun candidateView(
        stored: CandidateRow,
        activeOnly: Boolean,
    ): RecurringCandidateView =
        RecurringCandidateView(
            stored.candidate,
            stored.categoryName,
            evidence(stored.candidate.householdId, stored.candidate.id, activeOnly, lock = false),
        )

    private fun evidence(
        householdId: UUID,
        candidateId: UUID,
        activeOnly: Boolean,
        lock: Boolean,
    ): List<ExpenseObservation> =
        jdbc
            .sql(
                CANDIDATE_EVIDENCE +
                    (if (activeOnly) ACTIVE_EVIDENCE else "") +
                    " ORDER BY l.occurred_on, l.id" + if (lock) " FOR UPDATE OF l" else "",
            ).param("household", householdId)
            .param("candidate", candidateId)
            .query { row, _ ->
                ExpenseObservation(
                    row.getObject("id", UUID::class.java),
                    row.getObject("category_id", UUID::class.java),
                    row.getString("normalized_description"),
                    row.getObject("occurred_on", LocalDate::class.java),
                    Money.of(row.getBigDecimal("amount_kzt")),
                )
            }.list()

    private fun offset(value: Instant) = value.atOffset(ZoneOffset.UTC)

    private fun instant(
        row: java.sql.ResultSet,
        column: String,
    ): Instant = row.getObject(column, OffsetDateTime::class.java).toInstant()

    private data class CandidateRow(
        val candidate: RecurringExpenseCandidate,
        val categoryName: String,
    )

    companion object {
        private val OBSERVATIONS =
            """
            SELECT l.id, l.category_id, l.normalized_description, l.occurred_on, l.amount_kzt
            FROM ledger_entry l
            WHERE l.household_id = :household AND l.status = 'POSTED' AND l.direction = 'EXPENSE'
                AND l.normalized_description IS NOT NULL
                AND l.occurred_on >= :start AND l.occurred_on < :end
                AND NOT EXISTS (
                    SELECT 1 FROM recurring_occurrence_payment p
                    WHERE p.household_id = l.household_id AND p.ledger_entry_id = l.id
                )
                AND NOT EXISTS (
                    SELECT 1 FROM ledger_entry refund
                    WHERE refund.household_id = l.household_id AND refund.refund_of = l.id
                        AND refund.status = 'POSTED'
                )
                AND NOT EXISTS (
                    SELECT 1 FROM recurring_expense_candidate_evidence e
                    JOIN recurring_expense_candidate candidate
                        ON candidate.household_id = e.household_id AND candidate.id = e.candidate_id
                    WHERE e.household_id = l.household_id AND e.ledger_entry_id = l.id
                        AND candidate.status IN ('ACCEPTED', 'REJECTED')
                )
            ORDER BY l.normalized_description, l.category_id, l.occurred_on, l.id
            """.trimIndent()

        private val RULE_PAYMENTS =
            """
            SELECT o.rule_id, o.id AS occurrence_id, o.due_on, SUM(l.amount_kzt) AS paid_amount
            FROM recurring_occurrence o
            JOIN recurring_occurrence_payment p
                ON p.household_id = o.household_id AND p.occurrence_id = o.id
            JOIN ledger_entry l ON l.household_id = p.household_id AND l.id = p.ledger_entry_id
            WHERE o.household_id = :household AND o.status = 'SCHEDULED'
                AND o.due_on >= :start AND o.due_on < :end
                AND l.status = 'POSTED' AND l.direction = 'EXPENSE'
                AND NOT EXISTS (
                    SELECT 1 FROM ledger_entry refund
                    WHERE refund.household_id = l.household_id AND refund.refund_of = l.id
                        AND refund.status = 'POSTED'
                )
            GROUP BY o.rule_id, o.id, o.due_on
            HAVING SUM(l.amount_kzt) > 0
            ORDER BY o.rule_id, o.due_on, o.id
            """.trimIndent()

        private val CANDIDATES =
            """
            SELECT x.*, c.name AS category_name
            FROM recurring_expense_candidate x
            JOIN category c ON c.household_id = x.household_id AND c.id = x.category_id
            WHERE x.household_id = :household
            """.trimIndent()

        private val CANDIDATE_EVIDENCE =
            """
            SELECT l.id, l.category_id, l.normalized_description, l.occurred_on, l.amount_kzt
            FROM recurring_expense_candidate_evidence e
            JOIN ledger_entry l ON l.household_id = e.household_id AND l.id = e.ledger_entry_id
            WHERE e.household_id = :household AND e.candidate_id = :candidate
            """.trimIndent()

        private const val ACTIVE_EVIDENCE =
            " AND l.status = 'POSTED' AND l.direction = 'EXPENSE' AND NOT EXISTS (" +
                "SELECT 1 FROM ledger_entry refund WHERE refund.household_id = l.household_id " +
                "AND refund.refund_of = l.id AND refund.status = 'POSTED')"
    }
}
