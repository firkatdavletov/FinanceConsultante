CREATE TABLE category_usage_event (
    household_id UUID NOT NULL,
    member_id UUID NOT NULL,
    position BIGINT GENERATED ALWAYS AS IDENTITY,
    id UUID NOT NULL,
    operation_id UUID NOT NULL,
    category_id UUID NOT NULL,
    used_at_millis BIGINT NOT NULL CHECK (used_at_millis >= 0),
    PRIMARY KEY (household_id, member_id, position),
    UNIQUE (household_id, member_id, id),
    UNIQUE (household_id, member_id, operation_id),
    FOREIGN KEY (household_id, member_id) REFERENCES member(household_id, id) ON DELETE CASCADE
);
