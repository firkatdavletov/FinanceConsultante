ALTER TABLE household ADD COLUMN cash_reserve money_amount NOT NULL DEFAULT 0 CHECK (cash_reserve >= 0);

CREATE TABLE telegram_polling_state (
    bot_id BIGINT PRIMARY KEY CHECK (bot_id > 0),
    next_offset BIGINT NOT NULL DEFAULT 0 CHECK (next_offset >= 0),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((lease_token IS NULL) = (lease_until IS NULL))
);

CREATE TABLE telegram_inbox (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bot_id BIGINT NOT NULL REFERENCES telegram_polling_state(bot_id),
    update_id BIGINT NOT NULL CHECK (update_id >= 0),
    owner_id BIGINT,
    text TEXT CHECK (length(text) <= 4096),
    status TEXT NOT NULL CHECK (status IN ('RECEIVED', 'PROCESSED', 'IGNORED', 'FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (bot_id, update_id),
    CHECK (status <> 'RECEIVED' OR (owner_id IS NOT NULL AND owner_id > 0 AND text IS NOT NULL))
);
CREATE INDEX telegram_inbox_pending_idx ON telegram_inbox(bot_id, id) WHERE status = 'RECEIVED';

CREATE TABLE telegram_dialog (
    owner_id BIGINT PRIMARY KEY CHECK (owner_id > 0),
    step TEXT NOT NULL DEFAULT 'IDLE' CHECK (step IN ('IDLE', 'OPENING', 'DATE', 'REPORT_TIME', 'RESERVE')),
    initial_setup BOOLEAN NOT NULL DEFAULT FALSE,
    opening_balance money_amount,
    started_on DATE,
    report_time TIME NOT NULL DEFAULT '08:00' CHECK (extract(second FROM report_time) = 0),
    cash_reserve money_amount NOT NULL DEFAULT 0 CHECK (cash_reserve >= 0)
);

CREATE TABLE telegram_outbox (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    inbox_id BIGINT NOT NULL UNIQUE REFERENCES telegram_inbox(id),
    bot_id BIGINT NOT NULL REFERENCES telegram_polling_state(bot_id),
    owner_id BIGINT NOT NULL CHECK (owner_id > 0),
    text TEXT NOT NULL CHECK (length(text) BETWEEN 1 AND 4096),
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    telegram_message_id BIGINT,
    last_error TEXT CHECK (last_error ~ '^[a-z0-9_]{1,64}$'),
    CHECK ((status = 'SENDING') = (lease_token IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK (status <> 'SENT' OR telegram_message_id IS NOT NULL)
);
CREATE INDEX telegram_outbox_pending_idx ON telegram_outbox(bot_id, id) WHERE status IN ('PENDING', 'SENDING');

-- Keep the opening snapshot immutable once any financial history exists, including voided entries.
CREATE FUNCTION protect_account_opening() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.opening_balance, NEW.accounting_started_at) IS DISTINCT FROM
       (OLD.opening_balance, OLD.accounting_started_at)
       AND EXISTS (SELECT 1 FROM ledger_entry WHERE account_id = OLD.id) THEN
        RAISE EXCEPTION 'Opening snapshot is immutable after ledger entries' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_opening_immutable BEFORE UPDATE ON account
    FOR EACH ROW EXECUTE FUNCTION protect_account_opening();
