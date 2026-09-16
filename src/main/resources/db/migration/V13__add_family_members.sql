ALTER TABLE member DROP CONSTRAINT member_role_check;
ALTER TABLE member ADD CONSTRAINT member_role_check CHECK (role IN ('OWNER', 'MEMBER'));
ALTER TABLE member DROP CONSTRAINT member_household_id_role_key;
ALTER TABLE member DROP CONSTRAINT member_telegram_user_id_key;
ALTER TABLE member ADD COLUMN joined_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE member ADD COLUMN revoked_at TIMESTAMPTZ;
ALTER TABLE member ADD COLUMN reports_enabled BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE member SET reports_enabled = TRUE WHERE role = 'OWNER';
ALTER TABLE member ADD CONSTRAINT member_owner_cannot_be_revoked CHECK (role <> 'OWNER' OR revoked_at IS NULL);

CREATE UNIQUE INDEX member_active_telegram_user_idx ON member(telegram_user_id) WHERE revoked_at IS NULL;
CREATE UNIQUE INDEX member_household_owner_idx ON member(household_id) WHERE role = 'OWNER';
CREATE INDEX member_household_active_idx ON member(household_id, telegram_user_id) WHERE revoked_at IS NULL;

CREATE TABLE family_invitation (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL REFERENCES household(id),
    invited_telegram_user_id BIGINT NOT NULL CHECK (invited_telegram_user_id > 0),
    invited_by UUID NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED')),
    source_key TEXT NOT NULL CHECK (source_key ~ '^[A-Za-z0-9:_-]{1,128}$'),
    created_at TIMESTAMPTZ NOT NULL,
    responded_at TIMESTAMPTZ,
    FOREIGN KEY (household_id, invited_by) REFERENCES member(household_id, id),
    UNIQUE (household_id, source_key),
    CHECK ((status = 'PENDING') = (responded_at IS NULL))
);
CREATE UNIQUE INDEX family_invitation_pending_user_idx
    ON family_invitation(invited_telegram_user_id) WHERE status = 'PENDING';
CREATE INDEX family_invitation_household_idx ON family_invitation(household_id, created_at DESC);

ALTER TABLE telegram_inbox ADD COLUMN member_id UUID REFERENCES member(id);
UPDATE telegram_inbox inbox SET member_id = (
    SELECT member.id FROM member
    WHERE member.telegram_user_id = inbox.owner_id AND member.revoked_at IS NULL
);

ALTER TABLE telegram_outbox ADD COLUMN member_id UUID REFERENCES member(id);
UPDATE telegram_outbox outbox SET member_id = COALESCE(
    (SELECT inbox.member_id FROM telegram_inbox inbox WHERE inbox.id = outbox.inbox_id),
    (SELECT job.member_id FROM daily_report_job job WHERE job.id = outbox.report_job_id)
);

CREATE INDEX telegram_inbox_member_idx ON telegram_inbox(member_id) WHERE member_id IS NOT NULL;
CREATE INDEX telegram_outbox_member_pending_idx ON telegram_outbox(member_id)
    WHERE status IN ('PENDING', 'SENDING');

DO $$
DECLARE
    old_constraint TEXT;
BEGIN
    SELECT conname INTO old_constraint
    FROM pg_constraint
    WHERE conrelid = 'daily_report_job'::regclass
      AND contype = 'u'
      AND pg_get_constraintdef(oid) = 'UNIQUE (household_id, recipient_id, report_type, period_start)';
    IF old_constraint IS NOT NULL THEN
        EXECUTE format('ALTER TABLE daily_report_job DROP CONSTRAINT %I', old_constraint);
    END IF;
END $$;
ALTER TABLE daily_report_job ADD CONSTRAINT daily_report_job_membership_period_unique
    UNIQUE (household_id, member_id, report_type, period_start);
