ALTER TABLE household ADD COLUMN settings_version BIGINT NOT NULL DEFAULT 0 CHECK (settings_version >= 0);

CREATE TABLE daily_budget_snapshot (
    household_id UUID NOT NULL REFERENCES household(id) ON DELETE CASCADE,
    snapshot_date DATE NOT NULL,
    as_of TIMESTAMPTZ NOT NULL,
    balance money_amount NOT NULL,
    remaining_obligations money_amount NOT NULL CHECK (remaining_obligations >= 0),
    protected_reserve money_amount NOT NULL CHECK (protected_reserve >= 0),
    goal_reserve money_amount NOT NULL DEFAULT 0 CHECK (goal_reserve >= 0),
    free_amount money_amount NOT NULL,
    days_remaining SMALLINT NOT NULL CHECK (days_remaining BETWEEN 1 AND 31),
    original_daily_limit money_amount NOT NULL CHECK (original_daily_limit >= 0),
    ledger_revision BIGINT NOT NULL CHECK (ledger_revision >= 0),
    settings_version BIGINT NOT NULL CHECK (settings_version >= 0),
    recalculated BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (household_id, snapshot_date)
);

CREATE TABLE daily_report_job (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL REFERENCES household(id) ON DELETE CASCADE,
    member_id UUID NOT NULL,
    recipient_id BIGINT NOT NULL CHECK (recipient_id > 0),
    bot_id BIGINT NOT NULL CHECK (bot_id > 0),
    report_type TEXT NOT NULL DEFAULT 'DAILY' CHECK (report_type = 'DAILY'),
    period_start DATE NOT NULL,
    scheduled_for TIMESTAMPTZ NOT NULL,
    catch_up_until TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL DEFAULT 'SCHEDULED'
        CHECK (status IN ('SCHEDULED', 'PROCESSING', 'QUEUED', 'SENT', 'SKIPPED', 'FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (catch_up_until >= scheduled_for),
    CHECK (updated_at >= created_at),
    CHECK ((status = 'PROCESSING') = (lease_token IS NOT NULL AND lease_until IS NOT NULL)),
    FOREIGN KEY (household_id, member_id) REFERENCES member(household_id, id) ON DELETE CASCADE,
    UNIQUE (household_id, recipient_id, report_type, period_start),
    UNIQUE (household_id, id)
);

CREATE INDEX daily_report_job_due_idx ON daily_report_job(bot_id, next_attempt_at, scheduled_for)
    WHERE status IN ('SCHEDULED', 'PROCESSING');

ALTER TABLE telegram_outbox ALTER COLUMN inbox_id DROP NOT NULL;
ALTER TABLE telegram_outbox ADD COLUMN report_job_id UUID REFERENCES daily_report_job(id) ON DELETE CASCADE;
ALTER TABLE telegram_outbox ADD CONSTRAINT telegram_outbox_one_source
    CHECK ((inbox_id IS NOT NULL) <> (report_job_id IS NOT NULL));
CREATE UNIQUE INDEX telegram_outbox_report_job_idx ON telegram_outbox(report_job_id)
    WHERE report_job_id IS NOT NULL;
