-- NULL identifies pre-T05 inbox rows whose original Telegram date was not retained.
ALTER TABLE telegram_inbox ADD COLUMN message_sent_at TIMESTAMPTZ;
ALTER TABLE telegram_inbox ADD CONSTRAINT telegram_inbox_identity UNIQUE (id, bot_id, owner_id);
ALTER TABLE member ADD CONSTRAINT member_telegram_identity UNIQUE (household_id, id, telegram_user_id);

ALTER TABLE category ADD COLUMN dictionary_key TEXT
    CHECK (dictionary_key IN ('GROCERIES', 'HEALTH', 'SALARY'));
CREATE UNIQUE INDEX category_dictionary_key_idx ON category(household_id, dictionary_key);

-- A stable cursor orders history by recording order, even for backdated entries.
ALTER TABLE ledger_entry ADD COLUMN history_sequence BIGINT GENERATED ALWAYS AS IDENTITY;
CREATE UNIQUE INDEX ledger_history_idx ON ledger_entry(household_id, history_sequence DESC);

CREATE TABLE transaction_draft (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL REFERENCES household(id),
    member_id UUID NOT NULL,
    owner_id BIGINT NOT NULL,
    bot_id BIGINT NOT NULL,
    source_inbox_id BIGINT NOT NULL UNIQUE,
    state TEXT NOT NULL CHECK (state IN ('NEEDS_INPUT', 'READY', 'WAITING_RATE', 'CONFIRMED', 'CANCELLED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    amount money_amount CHECK (amount > 0),
    currency TEXT CHECK (currency IN ('KZT', 'RUB')),
    category_key TEXT CHECK (category_key IN ('GROCERIES', 'HEALTH', 'SALARY')),
    occurred_on DATE,
    reason TEXT NOT NULL CHECK (length(reason) BETWEEN 1 AND 512),
    entry_id UUID,
    FOREIGN KEY (household_id, member_id, owner_id) REFERENCES member(household_id, id, telegram_user_id),
    FOREIGN KEY (source_inbox_id, bot_id, owner_id) REFERENCES telegram_inbox(id, bot_id, owner_id),
    FOREIGN KEY (household_id, entry_id) REFERENCES ledger_entry(household_id, id),
    CHECK ((amount IS NULL AND currency IS NULL AND category_key IS NULL AND occurred_on IS NULL)
        OR (amount IS NOT NULL AND currency IS NOT NULL AND category_key IS NOT NULL AND occurred_on IS NOT NULL)),
    CHECK (state NOT IN ('READY', 'WAITING_RATE', 'CONFIRMED') OR amount IS NOT NULL),
    CHECK ((state = 'CONFIRMED') = (entry_id IS NOT NULL)),
    CHECK (state <> 'WAITING_RATE' OR currency = 'RUB')
);
CREATE UNIQUE INDEX transaction_draft_active_idx ON transaction_draft(bot_id, owner_id)
    WHERE state IN ('NEEDS_INPUT', 'READY', 'WAITING_RATE');
