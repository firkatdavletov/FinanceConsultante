ALTER TABLE daily_report_job DROP CONSTRAINT daily_report_job_report_type_check;
ALTER TABLE daily_report_job ADD CONSTRAINT daily_report_job_report_type_check
    CHECK (report_type IN ('DAILY', 'WEEKLY', 'MONTHLY'));

CREATE INDEX ledger_entry_analytics_period_idx
    ON ledger_entry(household_id, occurred_on, category_id)
    WHERE status = 'POSTED';
