-- Firebase identity is independent of budgets and legacy token/member bindings.
CREATE TABLE firebase_user (
    id UUID PRIMARY KEY,
    firebase_uid VARCHAR(128) NOT NULL UNIQUE CHECK (length(firebase_uid) > 0),
    display_name TEXT,
    email TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
