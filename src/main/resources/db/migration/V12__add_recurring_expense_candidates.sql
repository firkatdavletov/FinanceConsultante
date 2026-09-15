ALTER TABLE ledger_entry ADD COLUMN normalized_description TEXT
    CHECK (
        normalized_description IS NULL
        OR (
            length(normalized_description) BETWEEN 1 AND 160
            AND normalized_description = trim(normalized_description)
            AND normalized_description = lower(normalized_description)
            AND normalized_description !~ E'[\\r\\n]'
        )
    );

ALTER TABLE transaction_draft ADD COLUMN normalized_description TEXT
    CHECK (
        normalized_description IS NULL
        OR (
            length(normalized_description) BETWEEN 1 AND 160
            AND normalized_description = trim(normalized_description)
            AND normalized_description = lower(normalized_description)
            AND normalized_description !~ E'[\\r\\n]'
        )
    );

ALTER TABLE recurring_rule ADD COLUMN normalized_description TEXT
    CHECK (
        normalized_description IS NULL
        OR (
            length(normalized_description) BETWEEN 1 AND 160
            AND normalized_description = trim(normalized_description)
            AND normalized_description = lower(normalized_description)
            AND normalized_description !~ E'[\\r\\n]'
        )
    );

CREATE INDEX ledger_entry_recurring_detection_idx
    ON ledger_entry(household_id, normalized_description, category_id, occurred_on)
    WHERE status = 'POSTED' AND direction = 'EXPENSE' AND normalized_description IS NOT NULL;

CREATE TABLE recurring_expense_candidate (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL REFERENCES household(id),
    member_id UUID NOT NULL,
    category_id UUID NOT NULL,
    category_kind TEXT GENERATED ALWAYS AS ('EXPENSE'::text) STORED,
    normalized_description TEXT NOT NULL CHECK (
        length(normalized_description) BETWEEN 1 AND 160
        AND normalized_description = trim(normalized_description)
        AND normalized_description = lower(normalized_description)
        AND normalized_description !~ E'[\\r\\n]'
    ),
    frequency TEXT NOT NULL CHECK (frequency IN ('MONTHLY', 'WEEKLY')),
    schedule_value SMALLINT NOT NULL,
    suggested_amount money_amount NOT NULL CHECK (suggested_amount > 0),
    currency TEXT NOT NULL DEFAULT 'KZT' CHECK (currency = 'KZT'),
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    accepted_rule_id UUID,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (
        (frequency = 'MONTHLY' AND schedule_value BETWEEN 1 AND 31)
        OR (frequency = 'WEEKLY' AND schedule_value BETWEEN 1 AND 7)
    ),
    CHECK ((status = 'ACCEPTED') = (accepted_rule_id IS NOT NULL)),
    CHECK (updated_at >= created_at),
    FOREIGN KEY (household_id, member_id) REFERENCES member(household_id, id),
    FOREIGN KEY (household_id, category_id, category_kind) REFERENCES category(household_id, id, kind),
    FOREIGN KEY (household_id, accepted_rule_id) REFERENCES recurring_rule(household_id, id),
    UNIQUE (household_id, id),
    UNIQUE (household_id, normalized_description, category_id, frequency)
);

CREATE INDEX recurring_expense_candidate_pending_idx
    ON recurring_expense_candidate(household_id, updated_at DESC)
    WHERE status = 'PENDING';

CREATE TABLE recurring_expense_candidate_evidence (
    household_id UUID NOT NULL,
    candidate_id UUID NOT NULL,
    ledger_entry_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (candidate_id, ledger_entry_id),
    FOREIGN KEY (household_id, candidate_id)
        REFERENCES recurring_expense_candidate(household_id, id),
    FOREIGN KEY (household_id, ledger_entry_id)
        REFERENCES ledger_entry(household_id, id)
);

CREATE INDEX recurring_expense_candidate_evidence_entry_idx
    ON recurring_expense_candidate_evidence(household_id, ledger_entry_id);
