CREATE TABLE mobile_sync_state (
    household_id UUID PRIMARY KEY REFERENCES household(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0)
);

CREATE TABLE mobile_sync_change (
    household_id UUID NOT NULL REFERENCES household(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('LEDGER', 'CATEGORY')),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    snapshot JSONB NOT NULL,
    PRIMARY KEY (household_id, revision)
);

CREATE TABLE mobile_operation_request (
    household_id UUID NOT NULL REFERENCES household(id) ON DELETE CASCADE,
    member_id UUID NOT NULL,
    request_id UUID NOT NULL,
    payload_hash TEXT NOT NULL CHECK (length(payload_hash) = 64),
    entry_id UUID NOT NULL UNIQUE REFERENCES ledger_entry(id) ON DELETE CASCADE,
    PRIMARY KEY (household_id, member_id, request_id),
    FOREIGN KEY (household_id, member_id) REFERENCES member(household_id, id) ON DELETE CASCADE,
    FOREIGN KEY (household_id, entry_id) REFERENCES ledger_entry(household_id, id) ON DELETE CASCADE
);

-- A transactional row lock is held until commit. A later committed revision cannot
-- overtake an uncommitted one, including writes made by the Telegram handlers.
CREATE FUNCTION append_mobile_sync_change(budget UUID, entity_kind TEXT, removed BOOLEAN, body JSONB)
RETURNS VOID LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE next_revision BIGINT;
BEGIN
    INSERT INTO mobile_sync_state(household_id) VALUES (budget) ON CONFLICT DO NOTHING;
    UPDATE mobile_sync_state SET revision = revision + 1 WHERE household_id = budget
        RETURNING revision INTO next_revision;
    INSERT INTO mobile_sync_change(household_id, revision, kind, deleted, snapshot)
        VALUES (budget, next_revision, entity_kind, removed, body);
END $$;

-- Keep the backfill and trigger installation in this migration's transaction.
LOCK TABLE category, ledger_entry IN SHARE ROW EXCLUSIVE MODE;
SELECT append_mobile_sync_change(household_id, 'CATEGORY', FALSE, to_jsonb(c)) FROM category c ORDER BY id;
SELECT append_mobile_sync_change(household_id, 'LEDGER', FALSE, to_jsonb(l))
    FROM ledger_entry l ORDER BY history_sequence;

CREATE FUNCTION capture_mobile_sync_change() RETURNS TRIGGER LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM append_mobile_sync_change(OLD.household_id, TG_ARGV[0], TRUE, to_jsonb(OLD));
        RETURN OLD;
    END IF;
    IF TG_OP = 'INSERT' OR to_jsonb(OLD) IS DISTINCT FROM to_jsonb(NEW) THEN
        PERFORM append_mobile_sync_change(NEW.household_id, TG_ARGV[0], FALSE, to_jsonb(NEW));
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER mobile_ledger_change AFTER INSERT OR UPDATE OR DELETE ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION capture_mobile_sync_change('LEDGER');
CREATE TRIGGER mobile_category_change AFTER INSERT OR UPDATE OR DELETE ON category
    FOR EACH ROW EXECUTE FUNCTION capture_mobile_sync_change('CATEGORY');
