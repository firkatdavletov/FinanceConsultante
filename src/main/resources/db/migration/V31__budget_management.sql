CREATE TABLE firebase_budget_management (
    uid_hash CHAR(64) NOT NULL,
    request_id UUID NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    PRIMARY KEY(uid_hash, request_id)
);
CREATE TABLE firebase_deleted_budget (budget_id UUID PRIMARY KEY);
CREATE TABLE firebase_deleted_creation (
    uid_hash CHAR(64) NOT NULL,
    request_id UUID NOT NULL,
    PRIMARY KEY(uid_hash, request_id)
);
