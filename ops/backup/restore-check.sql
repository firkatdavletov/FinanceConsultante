\set ON_ERROR_STOP on

SELECT 'flyway_migrations' AS metric, count(*)::text AS value
FROM flyway_schema_history
UNION ALL
SELECT 'households', count(*)::text FROM household
UNION ALL
SELECT 'ledger_entries', count(*)::text FROM ledger_entry
UNION ALL
SELECT 'entry_revisions', count(*)::text FROM entry_revision
UNION ALL
SELECT 'savings_goals', count(*)::text FROM savings_goal
UNION ALL
SELECT 'savings_goal_allocations', count(*)::text FROM savings_goal_allocation
UNION ALL
SELECT 'goal_contribution_plans', count(*)::text FROM goal_contribution_plan
UNION ALL
SELECT 'telegram_idempotency_keys', count(*)::text FROM telegram_inbox
UNION ALL
SELECT 'report_jobs', count(*)::text FROM daily_report_job
ORDER BY metric;

SELECT
    account.household_id,
    count(ledger.id) AS ledger_entries,
    account.opening_balance + coalesce(
        sum(
            CASE
                WHEN ledger.status <> 'POSTED' THEN 0
                WHEN ledger.direction = 'INCOME' THEN ledger.amount_kzt
                WHEN ledger.direction = 'EXPENSE' THEN -ledger.amount_kzt
                WHEN ledger.direction = 'EXPENSE_REFUND' THEN ledger.amount_kzt
            END
        ),
        0
    ) AS balance_kzt
FROM account
LEFT JOIN ledger_entry ledger
    ON ledger.household_id = account.household_id AND ledger.account_id = account.id
GROUP BY account.household_id, account.opening_balance
ORDER BY account.household_id;
