CREATE TABLE recurring_rule (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL REFERENCES household(id),
    member_id UUID NOT NULL,
    category_id UUID NOT NULL,
    category_kind TEXT GENERATED ALWAYS AS ('EXPENSE'::text) STORED,
    frequency TEXT NOT NULL CHECK (frequency IN ('MONTHLY', 'WEEKLY')),
    schedule_value SMALLINT NOT NULL,
    amount money_amount NOT NULL CHECK (amount > 0),
    currency TEXT NOT NULL DEFAULT 'KZT' CHECK (currency = 'KZT'),
    starts_on DATE NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (
        (frequency = 'MONTHLY' AND schedule_value BETWEEN 1 AND 31)
        OR (frequency = 'WEEKLY' AND schedule_value BETWEEN 1 AND 7)
    ),
    CHECK (updated_at >= created_at),
    FOREIGN KEY (household_id, member_id) REFERENCES member(household_id, id),
    FOREIGN KEY (household_id, category_id, category_kind) REFERENCES category(household_id, id, kind),
    UNIQUE (household_id, id)
);

CREATE INDEX recurring_rule_active_idx ON recurring_rule(household_id, starts_on) WHERE active;

CREATE TABLE recurring_occurrence (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL,
    rule_id UUID NOT NULL,
    category_id UUID NOT NULL,
    category_kind TEXT GENERATED ALWAYS AS ('EXPENSE'::text) STORED,
    planned_amount money_amount NOT NULL CHECK (planned_amount > 0),
    currency TEXT NOT NULL DEFAULT 'KZT' CHECK (currency = 'KZT'),
    due_on DATE NOT NULL,
    status TEXT NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED', 'CANCELLED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (updated_at >= created_at),
    FOREIGN KEY (household_id, rule_id) REFERENCES recurring_rule(household_id, id),
    FOREIGN KEY (household_id, category_id, category_kind) REFERENCES category(household_id, id, kind),
    UNIQUE (rule_id, due_on),
    UNIQUE (household_id, id)
);

CREATE INDEX recurring_occurrence_due_idx ON recurring_occurrence(household_id, due_on)
    WHERE status = 'SCHEDULED';

CREATE TABLE recurring_occurrence_payment (
    household_id UUID NOT NULL,
    occurrence_id UUID NOT NULL,
    ledger_entry_id UUID NOT NULL,
    ledger_direction TEXT GENERATED ALWAYS AS ('EXPENSE'::text) STORED,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (occurrence_id, ledger_entry_id),
    FOREIGN KEY (household_id, occurrence_id) REFERENCES recurring_occurrence(household_id, id),
    FOREIGN KEY (household_id, ledger_entry_id, ledger_direction)
        REFERENCES ledger_entry(household_id, id, direction),
    UNIQUE (household_id, ledger_entry_id)
);

CREATE TABLE expected_income (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL REFERENCES household(id),
    member_id UUID NOT NULL,
    category_id UUID NOT NULL,
    category_kind TEXT GENERATED ALWAYS AS ('INCOME'::text) STORED,
    planned_amount money_amount NOT NULL CHECK (planned_amount > 0),
    currency TEXT NOT NULL DEFAULT 'KZT' CHECK (currency = 'KZT'),
    due_on DATE NOT NULL,
    status TEXT NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED', 'CANCELLED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (updated_at >= created_at),
    FOREIGN KEY (household_id, member_id) REFERENCES member(household_id, id),
    FOREIGN KEY (household_id, category_id, category_kind) REFERENCES category(household_id, id, kind),
    UNIQUE (household_id, id)
);

CREATE INDEX expected_income_due_idx ON expected_income(household_id, due_on)
    WHERE status = 'SCHEDULED';

CREATE TABLE expected_income_match (
    household_id UUID NOT NULL,
    expected_income_id UUID NOT NULL,
    ledger_entry_id UUID NOT NULL,
    ledger_direction TEXT GENERATED ALWAYS AS ('INCOME'::text) STORED,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (expected_income_id, ledger_entry_id),
    FOREIGN KEY (household_id, expected_income_id) REFERENCES expected_income(household_id, id),
    FOREIGN KEY (household_id, ledger_entry_id, ledger_direction)
        REFERENCES ledger_entry(household_id, id, direction),
    UNIQUE (household_id, ledger_entry_id)
);
