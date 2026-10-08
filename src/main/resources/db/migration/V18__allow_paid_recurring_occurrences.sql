ALTER TABLE recurring_occurrence DROP CONSTRAINT recurring_occurrence_status_check;
ALTER TABLE recurring_occurrence ADD CONSTRAINT recurring_occurrence_status_check
    CHECK (status IN ('SCHEDULED', 'PAID', 'CANCELLED'));
