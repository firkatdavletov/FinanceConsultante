CREATE TABLE internal_app_installation (
    device_id UUID PRIMARY KEY,
    active BOOLEAN NOT NULL,
    label TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
