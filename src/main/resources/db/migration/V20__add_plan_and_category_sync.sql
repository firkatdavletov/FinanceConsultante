-- Lock source tables before changing the journal constraint: existing v1 writers
-- must finish before the migration locks their trigger destination.
LOCK TABLE category, ledger_entry, recurring_rule, recurring_occurrence, expected_income,
    recurring_occurrence_payment, expected_income_match IN SHARE ROW EXCLUSIVE MODE;

ALTER TABLE mobile_sync_change DROP CONSTRAINT mobile_sync_change_kind_check;
ALTER TABLE mobile_sync_change ADD CONSTRAINT mobile_sync_change_kind_check CHECK (
    kind IN ('LEDGER', 'CATEGORY', 'RECURRING_RULE', 'RECURRING_OCCURRENCE',
             'EXPECTED_INCOME', 'OCCURRENCE_PAYMENT', 'INCOME_MATCH')
);

CREATE TABLE mobile_sync_command (
    household_id UUID NOT NULL REFERENCES household(id) ON DELETE CASCADE,
    member_id UUID NOT NULL,
    request_id UUID NOT NULL,
    payload_hash TEXT NOT NULL CHECK (length(payload_hash) = 64),
    revision BIGINT NOT NULL,
    entity_token BIGINT,
    PRIMARY KEY (household_id, member_id, request_id),
    FOREIGN KEY (household_id, member_id) REFERENCES member(household_id, id) ON DELETE CASCADE
);
CREATE INDEX mobile_sync_entity_revision_idx ON mobile_sync_change
    (household_id, kind, (snapshot ->> 'id'), revision DESC);

SELECT append_mobile_sync_change(household_id, 'RECURRING_RULE', FALSE, to_jsonb(r)) FROM recurring_rule r ORDER BY id;
SELECT append_mobile_sync_change(household_id, 'RECURRING_OCCURRENCE', FALSE, to_jsonb(o))
    FROM recurring_occurrence o ORDER BY id;
SELECT append_mobile_sync_change(household_id, 'EXPECTED_INCOME', FALSE, to_jsonb(i)) FROM expected_income i ORDER BY id;
SELECT append_mobile_sync_change(household_id, 'OCCURRENCE_PAYMENT', FALSE, to_jsonb(p))
    FROM recurring_occurrence_payment p ORDER BY occurrence_id, ledger_entry_id;
SELECT append_mobile_sync_change(household_id, 'INCOME_MATCH', FALSE, to_jsonb(m))
    FROM expected_income_match m ORDER BY expected_income_id, ledger_entry_id;

CREATE TRIGGER mobile_rule_change AFTER INSERT OR UPDATE OR DELETE ON recurring_rule
    FOR EACH ROW EXECUTE FUNCTION capture_mobile_sync_change('RECURRING_RULE');
CREATE TRIGGER mobile_occurrence_change AFTER INSERT OR UPDATE OR DELETE ON recurring_occurrence
    FOR EACH ROW EXECUTE FUNCTION capture_mobile_sync_change('RECURRING_OCCURRENCE');
CREATE TRIGGER mobile_expected_change AFTER INSERT OR UPDATE OR DELETE ON expected_income
    FOR EACH ROW EXECUTE FUNCTION capture_mobile_sync_change('EXPECTED_INCOME');
CREATE TRIGGER mobile_payment_change AFTER INSERT OR UPDATE OR DELETE ON recurring_occurrence_payment
    FOR EACH ROW EXECUTE FUNCTION capture_mobile_sync_change('OCCURRENCE_PAYMENT');
CREATE TRIGGER mobile_income_match_change AFTER INSERT OR UPDATE OR DELETE ON expected_income_match
    FOR EACH ROW EXECUTE FUNCTION capture_mobile_sync_change('INCOME_MATCH');
