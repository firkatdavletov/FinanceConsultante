-- Run read-only before V29; no amounts, descriptions or credentials are exported.
WITH links AS (
 SELECT household_id, ledger_entry_id, occurrence_id AS target_id, 'EXPENSE' AS kind FROM recurring_occurrence_payment
 UNION ALL
 SELECT household_id, ledger_entry_id, expected_income_id, 'INCOME' FROM expected_income_match
), problems AS (
 SELECT x.household_id, x.ledger_entry_id, 'multiple_targets' AS problem FROM links x
 GROUP BY x.household_id, x.ledger_entry_id HAVING count(*) > 1
 UNION ALL
 SELECT x.household_id, x.ledger_entry_id, 'invalid_reference' FROM links x
 LEFT JOIN ledger_entry l ON l.household_id=x.household_id AND l.id=x.ledger_entry_id
 LEFT JOIN recurring_occurrence o ON x.kind='EXPENSE' AND o.household_id=x.household_id AND o.id=x.target_id
 LEFT JOIN expected_income i ON x.kind='INCOME' AND i.household_id=x.household_id AND i.id=x.target_id
 WHERE l.id IS NULL OR l.direction<>x.kind OR l.category_id IS DISTINCT FROM COALESCE(o.category_id,i.category_id)
)
SELECT * FROM problems ORDER BY household_id, ledger_entry_id, problem;
