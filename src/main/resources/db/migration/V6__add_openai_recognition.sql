ALTER TABLE telegram_inbox DROP CONSTRAINT telegram_inbox_status_check;
ALTER TABLE telegram_inbox ADD CHECK (
    status IN ('RECEIVED', 'OPENAI_PENDING', 'PROCESSED', 'IGNORED', 'FAILED')
);
ALTER TABLE telegram_inbox ADD CONSTRAINT telegram_inbox_openai_payload CHECK (
    status <> 'OPENAI_PENDING' OR (owner_id IS NOT NULL AND owner_id > 0 AND text IS NOT NULL)
);
DROP INDEX telegram_inbox_pending_idx;
CREATE INDEX telegram_inbox_pending_idx ON telegram_inbox(bot_id, id)
    WHERE status IN ('RECEIVED', 'OPENAI_PENDING');

CREATE TABLE openai_recognition_job (
    id UUID PRIMARY KEY,
    inbox_id BIGINT NOT NULL UNIQUE,
    bot_id BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    household_id UUID NOT NULL,
    member_id UUID NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'BUDGET_EXHAUSTED')),
    prompt_version TEXT NOT NULL CHECK (prompt_version ~ '^[a-z0-9._-]{1,64}$'),
    schema_version TEXT NOT NULL CHECK (schema_version ~ '^[a-z0-9._-]{1,64}$'),
    model_id TEXT NOT NULL CHECK (length(model_id) BETWEEN 1 AND 128),
    usage_date DATE,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    request_id TEXT CHECK (length(request_id) BETWEEN 1 AND 128),
    response_id TEXT CHECK (length(response_id) BETWEEN 1 AND 128),
    input_tokens BIGINT CHECK (input_tokens >= 0),
    output_tokens BIGINT CHECK (output_tokens >= 0),
    total_tokens BIGINT CHECK (total_tokens >= 0),
    latency_ms BIGINT CHECK (latency_ms >= 0),
    last_error_code TEXT CHECK (last_error_code ~ '^[a-z0-9_:-]{1,64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    FOREIGN KEY (inbox_id, bot_id, owner_id) REFERENCES telegram_inbox(id, bot_id, owner_id),
    FOREIGN KEY (household_id, member_id, owner_id) REFERENCES member(household_id, id, telegram_user_id),
    CHECK ((status = 'PROCESSING') = (lease_token IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK (status NOT IN ('COMPLETED', 'FAILED', 'BUDGET_EXHAUSTED') OR completed_at IS NOT NULL),
    CHECK (total_tokens IS NULL OR total_tokens = input_tokens + output_tokens)
);
CREATE INDEX openai_recognition_pending_idx ON openai_recognition_job(inbox_id)
    WHERE status IN ('PENDING', 'PROCESSING');

CREATE TABLE openai_daily_usage (
    usage_date DATE PRIMARY KEY,
    request_units INTEGER NOT NULL DEFAULT 0 CHECK (request_units >= 0),
    input_tokens BIGINT NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens BIGINT NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    total_tokens BIGINT NOT NULL DEFAULT 0 CHECK (total_tokens = input_tokens + output_tokens),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
