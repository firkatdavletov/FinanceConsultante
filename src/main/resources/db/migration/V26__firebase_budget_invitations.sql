CREATE TABLE firebase_budget_invitation (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL REFERENCES household(id),
    invited_by UUID NOT NULL,
    code_hash CHAR(64) NOT NULL UNIQUE CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > created_at),
    revoked_at TIMESTAMPTZ,
    accepted_at TIMESTAMPTZ,
    FOREIGN KEY (household_id, invited_by) REFERENCES member(household_id, id),
    CHECK (revoked_at IS NULL OR accepted_at IS NULL)
);
CREATE UNIQUE INDEX firebase_budget_invitation_unused_idx ON firebase_budget_invitation(household_id)
    WHERE revoked_at IS NULL AND accepted_at IS NULL;
