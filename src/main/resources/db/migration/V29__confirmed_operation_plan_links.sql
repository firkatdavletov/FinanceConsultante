LOCK TABLE ledger_entry, recurring_occurrence, expected_income, recurring_occurrence_payment,
 expected_income_match IN SHARE ROW EXCLUSIVE MODE;
DO $$ BEGIN
 IF EXISTS (
 SELECT 1 FROM (
 SELECT household_id, ledger_entry_id FROM recurring_occurrence_payment
 UNION ALL SELECT household_id, ledger_entry_id FROM expected_income_match
 ) x GROUP BY household_id, ledger_entry_id HAVING count(*) > 1
 ) OR EXISTS (
 SELECT 1 FROM recurring_occurrence_payment p
 LEFT JOIN ledger_entry l ON l.household_id=p.household_id AND l.id=p.ledger_entry_id
 LEFT JOIN recurring_occurrence o ON o.household_id=p.household_id AND o.id=p.occurrence_id
 WHERE l.id IS NULL OR o.id IS NULL OR l.direction<>'EXPENSE' OR l.category_id<>o.category_id
 ) OR EXISTS (
 SELECT 1 FROM expected_income_match p
 LEFT JOIN ledger_entry l ON l.household_id=p.household_id AND l.id=p.ledger_entry_id
 LEFT JOIN expected_income i ON i.household_id=p.household_id AND i.id=p.expected_income_id
 WHERE l.id IS NULL OR i.id IS NULL OR l.direction<>'INCOME' OR l.category_id<>i.category_id
 ) THEN RAISE EXCEPTION 'Invalid historical plan links: run docs/sql/audit-operation-plan-links.sql and explicitly resolve conflicts'; END IF;
END $$;
ALTER TABLE household ADD COLUMN confirmed_plan_linking BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE mobile_sync_command ADD COLUMN response JSONB;
CREATE TABLE operation_plan_links (
 household_id UUID NOT NULL,
 ledger_id UUID NOT NULL,
 occurrence_id UUID,
 expected_income_id UUID,
 direction TEXT GENERATED ALWAYS AS (CASE WHEN occurrence_id IS NOT NULL THEN 'EXPENSE' ELSE 'INCOME' END) STORED,
 created_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(household_id, ledger_id),
 CHECK ((occurrence_id IS NULL) <> (expected_income_id IS NULL)),
 FOREIGN KEY(household_id,ledger_id,direction) REFERENCES ledger_entry(household_id,id,direction),
 FOREIGN KEY(household_id,occurrence_id) REFERENCES recurring_occurrence(household_id,id),
 FOREIGN KEY(household_id,expected_income_id) REFERENCES expected_income(household_id,id)
);
CREATE INDEX operation_plan_occurrence_idx ON operation_plan_links(household_id,occurrence_id);
CREATE INDEX operation_plan_income_idx ON operation_plan_links(household_id,expected_income_id);
INSERT INTO operation_plan_links(household_id,ledger_id,occurrence_id,created_at)
 SELECT household_id,ledger_entry_id,occurrence_id,created_at FROM recurring_occurrence_payment;
INSERT INTO operation_plan_links(household_id,ledger_id,expected_income_id,created_at)
 SELECT household_id,ledger_entry_id,expected_income_id,created_at FROM expected_income_match;
DROP TABLE recurring_occurrence_payment;
DROP TABLE expected_income_match;
-- Compatibility readers share the registry; these expression views are not independently writable.
CREATE VIEW recurring_occurrence_payment AS SELECT household_id,occurrence_id,ledger_id AS ledger_entry_id,
 'EXPENSE'::text AS ledger_direction,created_at FROM operation_plan_links WHERE occurrence_id IS NOT NULL;
CREATE VIEW expected_income_match AS SELECT household_id,expected_income_id,ledger_id AS ledger_entry_id,
 'INCOME'::text AS ledger_direction,created_at FROM operation_plan_links WHERE expected_income_id IS NOT NULL;
CREATE FUNCTION operation_plan_contribution(budget UUID, entry UUID) RETURNS NUMERIC
 LANGUAGE SQL STABLE SET search_path FROM CURRENT AS $$
 SELECT CASE WHEN l.status <> 'POSTED' THEN 0 WHEN l.direction='EXPENSE' THEN
 GREATEST(l.amount_kzt-COALESCE((SELECT SUM(r.amount_kzt) FROM ledger_entry r
 WHERE r.household_id=l.household_id AND r.refund_of=l.id AND r.status='POSTED'),0),0)
 ELSE l.amount_kzt END FROM ledger_entry l WHERE l.household_id=budget AND l.id=entry
$$;
CREATE FUNCTION capture_operation_plan_link() RETURNS TRIGGER LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE old_kind TEXT; new_kind TEXT;
BEGIN
 IF TG_OP <> 'INSERT' THEN
 old_kind := CASE WHEN OLD.occurrence_id IS NOT NULL THEN 'OCCURRENCE_PAYMENT' ELSE 'INCOME_MATCH' END;
 PERFORM append_mobile_sync_change(OLD.household_id,old_kind,TRUE,
 jsonb_build_object('occurrence_id',OLD.occurrence_id,'expected_income_id',OLD.expected_income_id,
 'ledger_entry_id',OLD.ledger_id,'created_at',OLD.created_at));
 END IF;
 IF TG_OP <> 'DELETE' THEN
 new_kind := CASE WHEN NEW.occurrence_id IS NOT NULL THEN 'OCCURRENCE_PAYMENT' ELSE 'INCOME_MATCH' END;
 PERFORM append_mobile_sync_change(NEW.household_id,new_kind,FALSE,
 jsonb_build_object('occurrence_id',NEW.occurrence_id,'expected_income_id',NEW.expected_income_id,
 'ledger_entry_id',NEW.ledger_id,'created_at',NEW.created_at));
 END IF;
 UPDATE household SET settings_version=settings_version+1 WHERE id=COALESCE(NEW.household_id,OLD.household_id);
 RETURN NULL;
END $$;
CREATE TRIGGER mobile_plan_link_change AFTER INSERT OR UPDATE OR DELETE ON operation_plan_links
 FOR EACH ROW EXECUTE FUNCTION capture_operation_plan_link();
-- Changing historical fulfillment semantics invalidates cached daily calculations.
UPDATE household SET settings_version=settings_version+1;

CREATE FUNCTION invalidate_plan_snapshot() RETURNS TRIGGER LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 UPDATE household SET settings_version=settings_version+1 WHERE id=COALESCE(NEW.household_id,OLD.household_id);
 RETURN NULL;
END $$;
CREATE TRIGGER invalidate_occurrence_snapshot AFTER INSERT OR UPDATE OR DELETE ON recurring_occurrence
 FOR EACH ROW EXECUTE FUNCTION invalidate_plan_snapshot();
CREATE TRIGGER invalidate_income_snapshot AFTER INSERT OR UPDATE OR DELETE ON expected_income
 FOR EACH ROW EXECUTE FUNCTION invalidate_plan_snapshot();
