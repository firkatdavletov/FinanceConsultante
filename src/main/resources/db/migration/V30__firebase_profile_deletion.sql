-- Durable outbox and minimal deletion receipt. No FK to the profile being erased.
CREATE TABLE firebase_profile_deletion (
    uid_hash CHAR(64) PRIMARY KEY CHECK (uid_hash ~ '^[0-9a-f]{64}$'),
    token_hash CHAR(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    firebase_uid VARCHAR(128),
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'COMPLETED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
    CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL)),
    CHECK ((status = 'COMPLETED') = (firebase_uid IS NULL))
);
CREATE INDEX firebase_profile_deletion_due ON firebase_profile_deletion(next_attempt_at)
    WHERE status = 'PENDING';

-- Explicitly ordered aggregate erasure; DELETE triggers remain enabled.
CREATE FUNCTION erase_firebase_budget(budget UUID) RETURNS VOID
LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    DELETE FROM telegram_outbox WHERE member_id IN (SELECT id FROM member WHERE household_id=budget)
        OR inbox_id IN (SELECT id FROM telegram_inbox WHERE member_id IN (SELECT id FROM member WHERE household_id=budget))
        OR report_job_id IN (SELECT id FROM daily_report_job WHERE household_id=budget);
    DELETE FROM openai_recognition_job WHERE household_id=budget;
    DELETE FROM transaction_draft WHERE household_id=budget;
    DELETE FROM telegram_entry_correction_dialog WHERE household_id=budget;
    DELETE FROM telegram_category_dialog WHERE category_id IN (SELECT id FROM category WHERE household_id=budget)
        OR owner_id IN (SELECT telegram_user_id FROM member WHERE household_id=budget);
    DELETE FROM telegram_dialog WHERE owner_id IN (SELECT telegram_user_id FROM member WHERE household_id=budget);
    DELETE FROM telegram_inbox WHERE member_id IN (SELECT id FROM member WHERE household_id=budget);
    DELETE FROM firebase_budget_creation WHERE household_id=budget;
    DELETE FROM firebase_budget_invitation WHERE household_id=budget;
    DELETE FROM firebase_budget_access WHERE household_id=budget;
    DELETE FROM mobile_budget_access WHERE household_id=budget;
    DELETE FROM family_invitation WHERE household_id=budget;
    DELETE FROM daily_report_job WHERE household_id=budget;
    DELETE FROM daily_budget_snapshot WHERE household_id=budget;
    DELETE FROM mobile_sync_command WHERE household_id=budget;
    DELETE FROM mobile_operation_request WHERE household_id=budget;
    DELETE FROM operation_plan_links WHERE household_id=budget;
    DELETE FROM category_rule WHERE household_id=budget;
    DELETE FROM savings_goal_allocation WHERE household_id=budget;
    DELETE FROM goal_contribution_plan WHERE household_id=budget;
    DELETE FROM savings_goal WHERE household_id=budget;
    DELETE FROM recurring_expense_candidate_evidence WHERE household_id=budget;
    DELETE FROM recurring_expense_candidate WHERE household_id=budget;
    DELETE FROM recurring_occurrence WHERE household_id=budget;
    DELETE FROM recurring_rule WHERE household_id=budget;
    DELETE FROM expected_income WHERE household_id=budget;
    DELETE FROM entry_revision WHERE household_id=budget;
    DELETE FROM ledger_entry WHERE household_id=budget;
    DELETE FROM category WHERE household_id=budget;
    DELETE FROM account WHERE household_id=budget;
    DELETE FROM member WHERE household_id=budget;
    DELETE FROM mobile_sync_change WHERE household_id=budget;
    DELETE FROM mobile_sync_state WHERE household_id=budget;
    DELETE FROM household WHERE id=budget;
END $$;
