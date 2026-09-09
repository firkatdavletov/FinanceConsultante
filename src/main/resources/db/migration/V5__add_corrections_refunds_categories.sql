ALTER TABLE ledger_entry ADD COLUMN modified_at TIMESTAMPTZ;
UPDATE ledger_entry SET modified_at = created_at;
ALTER TABLE ledger_entry ALTER COLUMN modified_at SET NOT NULL;
ALTER TABLE ledger_entry ALTER COLUMN modified_at SET DEFAULT CURRENT_TIMESTAMP;
DO $$
DECLARE constraint_name TEXT;
BEGIN
    FOR constraint_name IN SELECT conname FROM pg_constraint
        WHERE conrelid = 'ledger_entry'::regclass AND pg_get_constraintdef(oid) = 'CHECK ((occurred_at <= created_at))'
    LOOP
        EXECUTE format('ALTER TABLE ledger_entry DROP CONSTRAINT %I', constraint_name);
    END LOOP;
END $$;
ALTER TABLE ledger_entry ADD CHECK (occurred_at <= modified_at AND modified_at >= created_at);
ALTER TABLE ledger_entry ADD COLUMN refund_of UUID;
ALTER TABLE ledger_entry ADD COLUMN refund_direction TEXT GENERATED ALWAYS AS ('EXPENSE'::text) STORED;
ALTER TABLE ledger_entry ADD UNIQUE (household_id, id, direction);
ALTER TABLE ledger_entry ADD FOREIGN KEY (household_id, refund_of, refund_direction)
    REFERENCES ledger_entry(household_id, id, direction);
-- Existing unlinked refunds remain valid; all new linked refunds use an expense in the same budget.
ALTER TABLE ledger_entry ADD CHECK (refund_of IS NULL OR (direction = 'EXPENSE_REFUND' AND refund_of <> id));
CREATE INDEX ledger_refund_idx ON ledger_entry(household_id, refund_of) WHERE refund_of IS NOT NULL;
ALTER TABLE entry_revision ADD COLUMN reason TEXT NOT NULL DEFAULT 'INITIAL_RECORD';
ALTER TABLE entry_revision ADD COLUMN mutation_source TEXT;
CREATE UNIQUE INDEX revision_mutation_source_idx ON entry_revision(household_id, mutation_source);

ALTER TABLE category ADD UNIQUE (household_id, id);
CREATE TABLE category_rule (
    household_id UUID NOT NULL,
    keyword TEXT NOT NULL CHECK (keyword ~ '^[a-zа-яё]+(-[a-zа-яё]+)*$' AND length(keyword) <= 50),
    category_id UUID NOT NULL,
    corrected_entry_id UUID NOT NULL,
    corrected_version BIGINT NOT NULL,
    PRIMARY KEY (household_id, keyword),
    FOREIGN KEY (household_id, category_id) REFERENCES category(household_id, id),
    FOREIGN KEY (household_id, corrected_entry_id) REFERENCES ledger_entry(household_id, id),
    FOREIGN KEY (corrected_entry_id, corrected_version) REFERENCES entry_revision(entry_id, version)
);

ALTER TABLE transaction_draft DROP CONSTRAINT transaction_draft_category_key_check;
ALTER TABLE transaction_draft ADD COLUMN category_name TEXT;
ALTER TABLE transaction_draft ADD COLUMN category_direction TEXT CHECK (category_direction IN ('INCOME', 'EXPENSE'));
ALTER TABLE transaction_draft ADD COLUMN category_id UUID;
ALTER TABLE transaction_draft ADD FOREIGN KEY (household_id, category_id) REFERENCES category(household_id, id);
UPDATE transaction_draft SET category_name = CASE category_key
    WHEN 'GROCERIES' THEN 'Продукты' WHEN 'HEALTH' THEN 'Здоровье' WHEN 'SALARY' THEN 'Зарплата' END,
    category_direction = CASE WHEN category_key = 'SALARY' THEN 'INCOME' ELSE 'EXPENSE' END
    WHERE category_key IS NOT NULL;
ALTER TABLE transaction_draft ADD CHECK (
    (category_key IS NULL AND category_name IS NULL AND category_direction IS NULL AND category_id IS NULL)
    OR (category_key IS NOT NULL AND category_name IS NOT NULL AND category_direction IS NOT NULL
        AND ((category_key IN ('GROCERIES', 'HEALTH', 'SALARY') AND category_id IS NULL)
            OR (category_id IS NOT NULL AND category_key = category_id::text)))
);

ALTER TABLE telegram_inbox ADD COLUMN event_kind TEXT NOT NULL DEFAULT 'MESSAGE'
    CHECK (event_kind IN ('MESSAGE', 'CALLBACK', 'EDITED'));
ALTER TABLE telegram_inbox ADD COLUMN callback_query_id TEXT;
ALTER TABLE telegram_inbox ADD COLUMN telegram_message_id BIGINT;
CREATE UNIQUE INDEX telegram_callback_idx ON telegram_inbox(bot_id, callback_query_id);
ALTER TABLE telegram_outbox ADD COLUMN buttons JSONB NOT NULL DEFAULT '[]'::jsonb
    CHECK (jsonb_typeof(buttons) = 'array');
ALTER TABLE telegram_outbox ADD COLUMN callback_query_id TEXT;
