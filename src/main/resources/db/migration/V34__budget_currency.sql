ALTER TABLE household ADD COLUMN budget_currency TEXT NOT NULL DEFAULT 'KZT'
    CHECK (budget_currency IN ('KZT', 'RUB', 'USD', 'EUR'));

ALTER TABLE household ADD COLUMN budget_currency_version BIGINT NOT NULL DEFAULT 0
    CHECK (budget_currency_version >= 0);

CREATE FUNCTION capture_mobile_budget_currency() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    PERFORM append_mobile_sync_change(NEW.id, 'BUDGET_SETTINGS', FALSE,
        jsonb_build_object('id', NEW.id, 'opening_balance', a.opening_balance,
            'protected_reserve', NEW.cash_reserve))
        FROM account a WHERE a.household_id = NEW.id;
    RETURN NEW;
END $$;

CREATE TRIGGER mobile_budget_currency_change AFTER UPDATE OF budget_currency ON household
    FOR EACH ROW WHEN (OLD.budget_currency IS DISTINCT FROM NEW.budget_currency)
    EXECUTE FUNCTION capture_mobile_budget_currency();

-- Deliver the historical rate to clients that already have a cursor past these entries.
SELECT append_mobile_sync_change(household_id, 'LEDGER', FALSE, to_jsonb(l))
    FROM ledger_entry l WHERE rub_kzt IS NOT NULL ORDER BY household_id, history_sequence;
