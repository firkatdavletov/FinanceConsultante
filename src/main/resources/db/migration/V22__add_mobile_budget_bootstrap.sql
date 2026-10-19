-- Mobile members have no Telegram identity; existing member IDs and authorship are preserved.
ALTER TABLE member ALTER COLUMN telegram_user_id DROP NOT NULL;
CREATE TABLE mobile_budget_access (
    token_hash TEXT PRIMARY KEY CHECK (length(token_hash) = 64),
    household_id UUID NOT NULL REFERENCES household(id) ON DELETE CASCADE,
    member_id UUID NOT NULL,
    create_request_id UUID,
    create_payload_hash TEXT,
    FOREIGN KEY (household_id, member_id) REFERENCES member(household_id, id) ON DELETE CASCADE,
    CHECK ((create_request_id IS NULL) = (create_payload_hash IS NULL))
);
