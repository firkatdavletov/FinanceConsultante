DO $$
DECLARE
    constraint_name TEXT;
BEGIN
    SELECT conname INTO constraint_name
    FROM pg_constraint
    WHERE conrelid = 'transaction_draft'::regclass
      AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%NEEDS_INPUT%'
      AND pg_get_constraintdef(oid) LIKE '%WAITING_RATE%'
      AND pg_get_constraintdef(oid) LIKE '%CANCELLED%';
    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE transaction_draft DROP CONSTRAINT %I', constraint_name);
    END IF;
END $$;

ALTER TABLE transaction_draft ADD CONSTRAINT transaction_draft_state_check CHECK (
    state IN (
        'NEEDS_INPUT', 'NEEDS_CATEGORY', 'NEEDS_CATEGORY_NAME',
        'READY', 'WAITING_RATE', 'CONFIRMED', 'CANCELLED'
    )
);

ALTER TABLE transaction_draft DROP CONSTRAINT transaction_draft_ai_alias_check;
ALTER TABLE transaction_draft ADD CONSTRAINT transaction_draft_ai_alias_check CHECK (
    (NOT recognized_by_ai OR amount IS NOT NULL)
    AND (category_alias IS NULL OR amount IS NOT NULL)
);

DROP INDEX transaction_draft_needs_input_idx;
CREATE UNIQUE INDEX transaction_draft_conversation_idx ON transaction_draft(bot_id, owner_id)
    WHERE state IN ('NEEDS_INPUT', 'NEEDS_CATEGORY_NAME');

DROP INDEX transaction_draft_active_lookup_idx;
CREATE INDEX transaction_draft_active_lookup_idx
    ON transaction_draft(household_id, member_id, owner_id, bot_id, source_inbox_id)
    WHERE state IN ('NEEDS_INPUT', 'NEEDS_CATEGORY', 'NEEDS_CATEGORY_NAME', 'READY', 'WAITING_RATE');
