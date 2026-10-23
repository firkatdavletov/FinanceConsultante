ALTER TABLE firebase_budget_invitation
    ADD COLUMN accepted_by_uid VARCHAR(128) REFERENCES firebase_user(firebase_uid),
    ADD COLUMN accepted_member_id UUID,
    ADD CONSTRAINT firebase_invitation_accepted_member_fk
        FOREIGN KEY (household_id, accepted_member_id) REFERENCES member(household_id, id),
    ADD CONSTRAINT firebase_invitation_acceptance_pair
        CHECK ((accepted_by_uid IS NULL) = (accepted_member_id IS NULL)),
    ADD CONSTRAINT firebase_invitation_acceptance_timestamp
        CHECK (accepted_by_uid IS NULL OR accepted_at IS NOT NULL);
