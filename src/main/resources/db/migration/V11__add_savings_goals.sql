CREATE TABLE savings_goal (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL REFERENCES household(id),
    member_id UUID NOT NULL,
    name TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 100),
    target_amount money_amount NOT NULL CHECK (target_amount > 0),
    currency TEXT NOT NULL DEFAULT 'KZT' CHECK (currency = 'KZT'),
    deadline DATE NOT NULL,
    priority SMALLINT NOT NULL CHECK (priority BETWEEN 1 AND 5),
    allocated_amount money_amount NOT NULL DEFAULT 0 CHECK (
        allocated_amount >= 0 AND allocated_amount <= target_amount
    ),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    source_key TEXT NOT NULL CHECK (source_key ~ '^[A-Za-z0-9:_-]{1,128}$'),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (updated_at >= created_at),
    FOREIGN KEY (household_id, member_id) REFERENCES member(household_id, id),
    UNIQUE (household_id, id),
    UNIQUE (household_id, source_key)
);

CREATE INDEX savings_goal_overview_idx ON savings_goal(household_id, priority, deadline);

CREATE TABLE savings_goal_allocation (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    household_id UUID NOT NULL,
    goal_id UUID NOT NULL,
    action TEXT NOT NULL CHECK (action IN ('RESERVED', 'RELEASED', 'SPENT')),
    amount money_amount NOT NULL CHECK (amount > 0),
    source_key TEXT NOT NULL CHECK (source_key ~ '^[A-Za-z0-9:_-]{1,128}$'),
    ledger_entry_id UUID,
    ledger_direction TEXT GENERATED ALWAYS AS ('EXPENSE'::text) STORED,
    created_at TIMESTAMPTZ NOT NULL,
    CHECK ((action = 'SPENT') = (ledger_entry_id IS NOT NULL)),
    FOREIGN KEY (household_id, goal_id) REFERENCES savings_goal(household_id, id),
    FOREIGN KEY (household_id, ledger_entry_id, ledger_direction)
        REFERENCES ledger_entry(household_id, id, direction),
    UNIQUE (household_id, source_key),
    UNIQUE (household_id, ledger_entry_id)
);

CREATE INDEX savings_goal_allocation_goal_idx ON savings_goal_allocation(household_id, goal_id, id);

CREATE TABLE goal_contribution_plan (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL,
    goal_id UUID NOT NULL,
    amount money_amount NOT NULL CHECK (amount > 0),
    currency TEXT NOT NULL DEFAULT 'KZT' CHECK (currency = 'KZT'),
    due_on DATE NOT NULL,
    status TEXT NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED', 'CANCELLED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    source_key TEXT NOT NULL CHECK (source_key ~ '^[A-Za-z0-9:_-]{1,128}$'),
    cancel_source_key TEXT CHECK (cancel_source_key ~ '^[A-Za-z0-9:_-]{1,128}$'),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (updated_at >= created_at),
    CHECK ((status = 'CANCELLED') = (cancel_source_key IS NOT NULL)),
    FOREIGN KEY (household_id, goal_id) REFERENCES savings_goal(household_id, id),
    UNIQUE (household_id, id),
    UNIQUE (household_id, source_key),
    UNIQUE (household_id, cancel_source_key)
);

CREATE INDEX goal_contribution_plan_due_idx ON goal_contribution_plan(household_id, goal_id, due_on)
    WHERE status = 'SCHEDULED';
