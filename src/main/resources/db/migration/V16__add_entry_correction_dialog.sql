CREATE TABLE telegram_entry_correction_dialog (
    owner_id BIGINT PRIMARY KEY CHECK (owner_id > 0),
    household_id UUID NOT NULL,
    member_id UUID NOT NULL,
    entry_id UUID NOT NULL,
    entry_version BIGINT NOT NULL CHECK (entry_version >= 0),
    FOREIGN KEY (household_id, member_id) REFERENCES member(household_id, id),
    FOREIGN KEY (household_id, entry_id) REFERENCES ledger_entry(household_id, id)
);
