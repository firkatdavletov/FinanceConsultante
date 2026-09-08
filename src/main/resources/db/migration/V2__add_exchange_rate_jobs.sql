ALTER TABLE daily_exchange_rate ADD COLUMN quoted_at TIMESTAMPTZ;
UPDATE daily_exchange_rate SET quoted_at = fetched_at;
ALTER TABLE daily_exchange_rate ALTER COLUMN quoted_at SET NOT NULL;

CREATE TABLE exchange_rate_job (
    id UUID PRIMARY KEY,
    rate_date DATE NOT NULL UNIQUE,
    due_at TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PROCESSING', 'RETRY', 'COMPLETED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    last_error_code TEXT CHECK (last_error_code ~ '^[a-z0-9_:-]{1,64}$'),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CHECK (
        (status = 'PROCESSING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL AND completed_at IS NULL)
        OR (status IN ('PENDING', 'RETRY') AND lease_token IS NULL AND lease_until IS NULL AND completed_at IS NULL)
        OR (status = 'COMPLETED' AND lease_token IS NULL AND lease_until IS NULL AND completed_at IS NOT NULL)
    )
);

CREATE INDEX exchange_rate_job_due_idx ON exchange_rate_job(next_attempt_at, rate_date)
    WHERE status IN ('PENDING', 'RETRY');

CREATE INDEX exchange_rate_job_lease_idx ON exchange_rate_job(lease_until, rate_date)
    WHERE status = 'PROCESSING';
