package com.firkatdavletov.financeconsultante.ledger.infrastructure

import com.firkatdavletov.financeconsultante.ledger.application.CategoryRepository
import com.firkatdavletov.financeconsultante.ledger.application.CategoryRule
import com.firkatdavletov.financeconsultante.ledger.application.CategoryUsage
import com.firkatdavletov.financeconsultante.ledger.application.EntryTarget
import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

@Repository
@Transactional(propagation = Propagation.MANDATORY)
class JdbcCategoryRepository(
    private val jdbc: JdbcClient,
) : CategoryRepository {
    override fun categories(householdId: UUID): List<Category> =
        jdbc
            .sql("SELECT * FROM category WHERE household_id = ? ORDER BY name, id")
            .param(householdId)
            .query { row, _ ->
                Category(
                    row.getObject("id", UUID::class.java),
                    householdId,
                    row.getString("name"),
                    CategoryKind.valueOf(row.getString("kind")),
                    row.getBoolean("archived"),
                    row.getString("dictionary_key"),
                    row.getString("icon_code"),
                )
            }.list()

    override fun rules(householdId: UUID): List<CategoryRule> =
        jdbc
            .sql("SELECT * FROM category_rule WHERE household_id = ? ORDER BY keyword")
            .param(householdId)
            .query {
                row,
                _,
                ->
                CategoryRule(row.getString("keyword"), row.getObject("category_id", UUID::class.java))
            }.list()

    override fun create(category: Category): Boolean =
        jdbc
            .sql(
                "INSERT INTO category(id, household_id, name, kind, icon_code) VALUES (?, ?, ?, ?, ?) " +
                    "ON CONFLICT DO NOTHING",
            ).params(category.id, category.householdId, category.name, category.kind.name, category.icon)
            .update() == 1

    override fun archive(
        householdId: UUID,
        id: UUID,
        archived: Boolean,
    ): Boolean =
        jdbc
            .sql("UPDATE category SET archived = ? WHERE household_id = ? AND id = ?")
            .params(archived, householdId, id)
            .update() == 1

    override fun rename(
        householdId: UUID,
        id: UUID,
        name: String,
    ): Boolean =
        jdbc
            .sql("UPDATE category SET name = ? WHERE household_id = ? AND id = ?")
            .params(name, householdId, id)
            .update() == 1

    override fun changeIcon(
        householdId: UUID,
        id: UUID,
        icon: String,
    ): Boolean =
        jdbc
            .sql("UPDATE category SET icon_code = ? WHERE household_id = ? AND id = ? AND NOT archived")
            .params(icon, householdId, id)
            .update() == 1

    override fun usage(
        householdId: UUID,
        id: UUID,
        startInclusive: LocalDate,
        endExclusive: LocalDate,
    ): CategoryUsage =
        jdbc
            .sql(
                """
                SELECT COALESCE(SUM(CASE direction WHEN 'INCOME' THEN amount_kzt
                    WHEN 'EXPENSE' THEN amount_kzt WHEN 'EXPENSE_REFUND' THEN -amount_kzt ELSE 0 END)
                    FILTER (WHERE occurred_on >= :start AND occurred_on < :end), 0) AS month_expenses,
                    COUNT(*) FILTER (WHERE occurred_on >= :start AND occurred_on < :end) AS month_operations,
                    COUNT(*) AS total_operations
                FROM ledger_entry
                WHERE household_id = :household AND category_id = :category AND status = 'POSTED'
                """.trimIndent(),
            ).param("start", startInclusive)
            .param("end", endExclusive)
            .param("household", householdId)
            .param("category", id)
            .query { row, _ ->
                CategoryUsage(
                    Money.of(row.getBigDecimal("month_expenses")),
                    row.getLong("month_operations"),
                    row.getLong("total_operations"),
                )
            }.single()

    override fun saveRule(
        target: EntryTarget,
        keyword: String,
    ): Boolean =
        jdbc
            .sql(
                """
                INSERT INTO category_rule(household_id, keyword, category_id, corrected_entry_id, corrected_version)
                SELECT l.household_id, :keyword, l.category_id, l.id, l.version FROM ledger_entry l
                JOIN category c ON c.household_id = l.household_id AND c.id = l.category_id
                JOIN entry_revision r ON r.entry_id = l.id AND r.version = l.version
                WHERE l.household_id = :household AND l.id = :id AND l.version = :version AND l.status = 'POSTED'
                    AND NOT c.archived AND r.reason = 'CATEGORY_CHANGED'
                ON CONFLICT (household_id, keyword) DO UPDATE SET category_id = EXCLUDED.category_id,
                    corrected_entry_id = EXCLUDED.corrected_entry_id, corrected_version = EXCLUDED.corrected_version
                """.trimIndent(),
            ).param("keyword", keyword)
            .param("household", target.householdId)
            .param("id", target.entryId)
            .param("version", target.version)
            .update() == 1

    override fun saveLearnedRule(
        target: EntryTarget,
        alias: String,
    ): Boolean =
        jdbc
            .sql(
                """
                INSERT INTO category_rule(household_id, keyword, category_id, corrected_entry_id, corrected_version)
                SELECT l.household_id, :alias, l.category_id, l.id, l.version FROM ledger_entry l
                JOIN category c ON c.household_id = l.household_id AND c.id = l.category_id
                JOIN entry_revision r ON r.entry_id = l.id AND r.version = l.version
                WHERE l.household_id = :household AND l.actor_id = :member
                    AND l.id = :id AND l.version = :version AND l.status = 'POSTED' AND NOT c.archived
                ON CONFLICT (household_id, keyword) DO UPDATE SET category_id = EXCLUDED.category_id,
                    corrected_entry_id = EXCLUDED.corrected_entry_id, corrected_version = EXCLUDED.corrected_version
                """.trimIndent(),
            ).param("alias", alias)
            .param("household", target.householdId)
            .param("member", target.memberId)
            .param("id", target.entryId)
            .param("version", target.version)
            .update() == 1

    override fun deleteRule(
        householdId: UUID,
        keyword: String,
    ) {
        jdbc
            .sql(
                "DELETE FROM category_rule WHERE household_id = ? AND keyword = ?",
            ).params(householdId, keyword)
            .update()
    }

    override fun deleteRules(
        householdId: UUID,
        categoryId: UUID,
    ) {
        jdbc
            .sql("DELETE FROM category_rule WHERE household_id = ? AND category_id = ?")
            .params(householdId, categoryId)
            .update()
    }
}
