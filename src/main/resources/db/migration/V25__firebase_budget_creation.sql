-- A receipt and all initial budget data commit together. A UID may own many budgets.
CREATE TABLE firebase_budget_creation (
    firebase_uid VARCHAR(128) NOT NULL,
    request_id UUID NOT NULL,
    payload_hash CHAR(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    household_id UUID NOT NULL,
    PRIMARY KEY (firebase_uid, request_id),
    UNIQUE (household_id),
    FOREIGN KEY (firebase_uid, household_id)
        REFERENCES firebase_budget_access(firebase_uid, household_id)
);
