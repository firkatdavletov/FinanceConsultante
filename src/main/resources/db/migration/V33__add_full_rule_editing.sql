ALTER TABLE recurring_rule
    ADD COLUMN rule_name TEXT CHECK (rule_name IS NULL OR
        (rule_name = btrim(rule_name) AND length(rule_name) BETWEEN 1 AND 160)),
    ADD COLUMN effective_on DATE;

ALTER TABLE recurring_occurrence ADD COLUMN schedule_cancelled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE income_rule (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL REFERENCES household(id) ON DELETE CASCADE,
    member_id UUID NOT NULL,
    category_id UUID NOT NULL,
    category_kind TEXT GENERATED ALWAYS AS ('INCOME'::text) STORED,
    amount money_amount NOT NULL CHECK (amount > 0),
    due_on DATE NOT NULL,
    income_name TEXT NOT NULL CHECK (income_name = btrim(income_name) AND length(income_name) BETWEEN 1 AND 160),
    income_frequency TEXT NOT NULL CHECK (income_frequency IN ('ONCE','WEEKLY','BIWEEKLY','MONTHLY')),
    income_active BOOLEAN NOT NULL,
    status TEXT NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED','CANCELLED')),
    effective_on DATE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL CHECK (updated_at >= created_at),
    UNIQUE (household_id,id),
    FOREIGN KEY (household_id,member_id) REFERENCES member(household_id,id) ON DELETE CASCADE,
    FOREIGN KEY (household_id,category_id,category_kind) REFERENCES category(household_id,id,kind) ON DELETE CASCADE
);
ALTER TABLE expected_income
    ADD COLUMN rule_id UUID,
    ADD COLUMN scheduled_on DATE,
    ADD COLUMN income_name TEXT CHECK (income_name IS NULL OR
        (income_name = btrim(income_name) AND length(income_name) BETWEEN 1 AND 160)),
    ADD COLUMN income_frequency TEXT CHECK (income_frequency IN ('ONCE','WEEKLY','BIWEEKLY','MONTHLY')),
    ADD COLUMN income_active BOOLEAN,
    ADD COLUMN schedule_cancelled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT expected_income_rule_fk FOREIGN KEY (household_id,rule_id)
        REFERENCES income_rule(household_id,id),
    ADD CONSTRAINT expected_income_rule_date_check CHECK ((rule_id IS NULL) = (scheduled_on IS NULL)),
    ADD CONSTRAINT expected_income_rule_date_unique UNIQUE (rule_id,scheduled_on);

ALTER TABLE mobile_sync_change DROP CONSTRAINT mobile_sync_change_kind_check;
ALTER TABLE mobile_sync_change ADD CONSTRAINT mobile_sync_change_kind_check CHECK (
    kind IN ('LEDGER','CATEGORY','RECURRING_RULE','RECURRING_OCCURRENCE','EXPECTED_INCOME',
        'OCCURRENCE_PAYMENT','INCOME_MATCH','BUDGET_SETTINGS','SAVINGS_GOAL','VARIABLE_SPENDING_EXCLUSION','INCOME_RULE')
);
CREATE TRIGGER mobile_income_rule_change AFTER INSERT OR UPDATE OR DELETE ON income_rule
    FOR EACH ROW EXECUTE FUNCTION capture_mobile_sync_change('INCOME_RULE');
CREATE TRIGGER invalidate_income_rule_snapshot AFTER INSERT OR UPDATE OR DELETE ON income_rule
    FOR EACH ROW EXECUTE FUNCTION invalidate_plan_snapshot();
