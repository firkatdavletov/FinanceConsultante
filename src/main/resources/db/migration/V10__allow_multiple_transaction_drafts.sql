DROP INDEX transaction_draft_active_idx;

-- A complete proposal belongs to its own source inbox and may wait independently for confirmation.
-- Only an incomplete clarification remains a single conversation per owner and bot.
CREATE UNIQUE INDEX transaction_draft_needs_input_idx ON transaction_draft(bot_id, owner_id)
    WHERE state = 'NEEDS_INPUT';

CREATE INDEX transaction_draft_active_lookup_idx
    ON transaction_draft(household_id, member_id, owner_id, bot_id, source_inbox_id)
    WHERE state IN ('NEEDS_INPUT', 'READY', 'WAITING_RATE');
