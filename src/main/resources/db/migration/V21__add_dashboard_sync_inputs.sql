-- All inputs are journaled in the same transaction as the financial change.
-- Source locks precede the journal lock, as in V20.
LOCK TABLE household, account, savings_goal, recurring_expense_candidate,
    recurring_expense_candidate_evidence, ledger_entry, category, recurring_rule,
    recurring_occurrence, expected_income, recurring_occurrence_payment,
    expected_income_match IN SHARE ROW EXCLUSIVE MODE;

ALTER TABLE mobile_sync_change DROP CONSTRAINT mobile_sync_change_kind_check;
ALTER TABLE mobile_sync_change ADD CONSTRAINT mobile_sync_change_kind_check CHECK (
    kind IN ('LEDGER', 'CATEGORY', 'RECURRING_RULE', 'RECURRING_OCCURRENCE',
             'EXPECTED_INCOME', 'OCCURRENCE_PAYMENT', 'INCOME_MATCH',
             'BUDGET_SETTINGS', 'SAVINGS_GOAL', 'VARIABLE_SPENDING_EXCLUSION')
);

CREATE FUNCTION capture_mobile_budget_settings() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE budget UUID; body JSONB;
BEGIN
    IF TG_TABLE_NAME = 'household' THEN
        IF OLD.cash_reserve IS NOT DISTINCT FROM NEW.cash_reserve THEN RETURN NEW; END IF;
        budget := NEW.id;
    ELSE
        IF TG_OP = 'DELETE' THEN
            PERFORM append_mobile_sync_change(OLD.household_id, 'BUDGET_SETTINGS', TRUE,
                jsonb_build_object('id', OLD.household_id, 'opening_balance', OLD.opening_balance,
                    'protected_reserve', 0));
            RETURN OLD;
        END IF;
        budget := NEW.household_id;
    END IF;
    SELECT jsonb_build_object('id', h.id, 'opening_balance', a.opening_balance,
        'protected_reserve', h.cash_reserve) INTO body
        FROM household h JOIN account a ON a.household_id = h.id WHERE h.id = budget;
    IF body IS NOT NULL THEN
        PERFORM append_mobile_sync_change(budget, 'BUDGET_SETTINGS', FALSE, body);
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION append_mobile_variable_exclusion(budget UUID, entry UUID) RETURNS VOID
LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE excluded BOOLEAN;
BEGIN
    SELECT EXISTS (
        SELECT 1 FROM recurring_expense_candidate_evidence e
        JOIN recurring_expense_candidate c ON c.household_id = e.household_id AND c.id = e.candidate_id
        WHERE e.household_id = budget AND e.ledger_entry_id = entry AND c.status = 'ACCEPTED'
    ) INTO excluded;
    PERFORM append_mobile_sync_change(budget, 'VARIABLE_SPENDING_EXCLUSION', NOT excluded,
        jsonb_build_object('id', entry));
END $$;

CREATE FUNCTION capture_mobile_variable_exclusion() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE entry UUID;
BEGIN
    IF TG_TABLE_NAME = 'recurring_expense_candidate_evidence' THEN
        IF TG_OP <> 'INSERT' THEN
            PERFORM append_mobile_variable_exclusion(OLD.household_id, OLD.ledger_entry_id);
        END IF;
        IF TG_OP <> 'DELETE' THEN
            PERFORM append_mobile_variable_exclusion(NEW.household_id, NEW.ledger_entry_id);
        END IF;
    ELSE
        FOR entry IN SELECT DISTINCT ledger_entry_id FROM recurring_expense_candidate_evidence
            WHERE household_id = NEW.household_id AND candidate_id = NEW.id
        LOOP
            PERFORM append_mobile_variable_exclusion(NEW.household_id, entry);
        END LOOP;
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;

SELECT append_mobile_sync_change(h.id, 'BUDGET_SETTINGS', FALSE,
    jsonb_build_object('id', h.id, 'opening_balance', a.opening_balance, 'protected_reserve', h.cash_reserve))
    FROM household h JOIN account a ON a.household_id = h.id ORDER BY h.id;
SELECT append_mobile_sync_change(household_id, 'SAVINGS_GOAL', FALSE, to_jsonb(g))
    FROM savings_goal g ORDER BY id;
SELECT append_mobile_variable_exclusion(e.household_id, e.ledger_entry_id)
    FROM recurring_expense_candidate_evidence e
    JOIN recurring_expense_candidate c ON c.household_id = e.household_id AND c.id = e.candidate_id
    WHERE c.status = 'ACCEPTED' GROUP BY e.household_id, e.ledger_entry_id
    ORDER BY e.household_id, e.ledger_entry_id;

CREATE TRIGGER mobile_account_settings_change AFTER INSERT OR UPDATE OF opening_balance OR DELETE ON account
    FOR EACH ROW EXECUTE FUNCTION capture_mobile_budget_settings();
CREATE TRIGGER mobile_household_settings_change AFTER UPDATE OF cash_reserve ON household
    FOR EACH ROW EXECUTE FUNCTION capture_mobile_budget_settings();
CREATE TRIGGER mobile_goal_change AFTER INSERT OR UPDATE OR DELETE ON savings_goal
    FOR EACH ROW EXECUTE FUNCTION capture_mobile_sync_change('SAVINGS_GOAL');
CREATE TRIGGER mobile_candidate_classification_change AFTER UPDATE OF status ON recurring_expense_candidate
    FOR EACH ROW EXECUTE FUNCTION capture_mobile_variable_exclusion();
CREATE TRIGGER mobile_candidate_evidence_change AFTER INSERT OR UPDATE OR DELETE ON recurring_expense_candidate_evidence
    FOR EACH ROW EXECUTE FUNCTION capture_mobile_variable_exclusion();
