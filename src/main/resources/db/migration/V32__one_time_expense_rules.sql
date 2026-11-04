ALTER TABLE recurring_rule
    DROP CONSTRAINT recurring_rule_frequency_check,
    DROP CONSTRAINT recurring_rule_check,
    ALTER COLUMN schedule_value DROP NOT NULL;

ALTER TABLE recurring_rule
    ADD CONSTRAINT recurring_rule_frequency_check CHECK (frequency IN ('MONTHLY', 'WEEKLY', 'ONCE')),
    ADD CONSTRAINT recurring_rule_schedule_check CHECK (
        (frequency = 'ONCE' AND schedule_value IS NULL)
        OR (frequency = 'MONTHLY' AND schedule_value IS NOT NULL AND schedule_value BETWEEN 1 AND 31)
        OR (frequency = 'WEEKLY' AND schedule_value IS NOT NULL AND schedule_value BETWEEN 1 AND 7)
    );
