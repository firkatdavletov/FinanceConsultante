-- Keeps every existing household/member UUID and financial receipt unchanged.
ALTER TABLE household ADD COLUMN budget_name TEXT NOT NULL DEFAULT 'Мой бюджет'
    CHECK (length(budget_name) BETWEEN 1 AND 80);
CREATE TABLE firebase_budget_access (
    firebase_uid VARCHAR(128) NOT NULL REFERENCES firebase_user(firebase_uid),
    household_id UUID NOT NULL REFERENCES household(id),
    member_id UUID NOT NULL UNIQUE,
    PRIMARY KEY (firebase_uid, household_id),
    FOREIGN KEY (household_id, member_id) REFERENCES member(household_id, id)
);
