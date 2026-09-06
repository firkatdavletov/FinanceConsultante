-- Unconstrained NUMERIC is intentional: NUMERIC(19,2) rounds before CHECK runs.
CREATE DOMAIN money_amount AS NUMERIC
    CHECK (abs(VALUE) <= 99999999999999999.99 AND scale(VALUE) <= 2);

CREATE DOMAIN rub_kzt_rate AS NUMERIC
    CHECK (VALUE > 0 AND VALUE <= 99999999999.99999999 AND scale(VALUE) <= 8);

CREATE TABLE household (
    id UUID PRIMARY KEY,
    base_currency TEXT NOT NULL DEFAULT 'KZT' CHECK (base_currency = 'KZT'),
    time_zone TEXT NOT NULL DEFAULT 'Asia/Almaty' CHECK (time_zone = 'Asia/Almaty'),
    report_time TIME NOT NULL DEFAULT '08:00' CHECK (extract(second FROM report_time) = 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE member (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL REFERENCES household(id),
    telegram_user_id BIGINT NOT NULL UNIQUE CHECK (telegram_user_id > 0),
    role TEXT NOT NULL DEFAULT 'OWNER' CHECK (role = 'OWNER'),
    UNIQUE (household_id, id),
    UNIQUE (household_id, role)
);

CREATE TABLE account (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL UNIQUE REFERENCES household(id),
    currency TEXT NOT NULL DEFAULT 'KZT' CHECK (currency = 'KZT'),
    opening_balance money_amount NOT NULL,
    accounting_started_at TIMESTAMPTZ NOT NULL,
    UNIQUE (household_id, id, accounting_started_at)
);

CREATE TABLE category (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL REFERENCES household(id),
    name TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 100),
    kind TEXT NOT NULL CHECK (kind IN ('INCOME', 'EXPENSE')),
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (household_id, id, kind),
    UNIQUE (household_id, kind, name)
);

-- One immutable daily snapshot: KZT per one RUB, including weekends.
CREATE TABLE daily_exchange_rate (
    rate_date DATE PRIMARY KEY,
    rub_kzt rub_kzt_rate NOT NULL,
    source TEXT NOT NULL CHECK (source ~ '^[A-Za-z0-9:_-]{1,64}$'),
    fetched_at TIMESTAMPTZ NOT NULL,
    UNIQUE (rate_date, rub_kzt)
);

CREATE TABLE ledger_entry (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL REFERENCES household(id),
    account_id UUID NOT NULL,
    accounting_started_at TIMESTAMPTZ NOT NULL,
    category_id UUID NOT NULL,
    direction TEXT NOT NULL CHECK (direction IN ('INCOME', 'EXPENSE', 'EXPENSE_REFUND')),
    category_kind TEXT GENERATED ALWAYS AS (
        CASE WHEN direction = 'INCOME' THEN 'INCOME' ELSE 'EXPENSE' END
    ) STORED,
    original_amount money_amount NOT NULL CHECK (original_amount > 0),
    original_currency TEXT NOT NULL CHECK (original_currency IN ('KZT', 'RUB')),
    amount_kzt money_amount NOT NULL CHECK (amount_kzt > 0),
    occurred_at TIMESTAMPTZ NOT NULL CHECK (occurred_at >= accounting_started_at),
    occurred_on DATE GENERATED ALWAYS AS ((occurred_at AT TIME ZONE 'Asia/Almaty')::DATE) STORED,
    rub_kzt rub_kzt_rate,
    status TEXT NOT NULL DEFAULT 'POSTED' CHECK (status IN ('POSTED', 'VOIDED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    source_key TEXT NOT NULL CHECK (source_key ~ '^[A-Za-z0-9:_-]{1,128}$'),
    actor_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CHECK (occurred_at <= created_at),
    CHECK (
        (original_currency = 'KZT' AND rub_kzt IS NULL AND amount_kzt = original_amount)
        OR (original_currency = 'RUB' AND rub_kzt IS NOT NULL
            AND amount_kzt = round(original_amount * rub_kzt, 2))
    ),
    FOREIGN KEY (household_id, account_id, accounting_started_at)
        REFERENCES account(household_id, id, accounting_started_at),
    FOREIGN KEY (household_id, category_id, category_kind) REFERENCES category(household_id, id, kind),
    FOREIGN KEY (household_id, actor_id) REFERENCES member(household_id, id),
    FOREIGN KEY (occurred_on, rub_kzt) REFERENCES daily_exchange_rate(rate_date, rub_kzt),
    UNIQUE (household_id, source_key),
    UNIQUE (household_id, id)
);

CREATE INDEX ledger_entry_household_occurred_at_idx ON ledger_entry(household_id, occurred_at);

CREATE TABLE entry_revision (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    household_id UUID NOT NULL,
    entry_id UUID NOT NULL,
    version BIGINT NOT NULL CHECK (version >= 0),
    actor_id UUID NOT NULL,
    action TEXT NOT NULL CHECK (action IN ('CREATED', 'CORRECTED', 'VOIDED')),
    snapshot JSONB NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (household_id, entry_id) REFERENCES ledger_entry(household_id, id),
    FOREIGN KEY (household_id, actor_id) REFERENCES member(household_id, id),
    UNIQUE (entry_id, version)
);
