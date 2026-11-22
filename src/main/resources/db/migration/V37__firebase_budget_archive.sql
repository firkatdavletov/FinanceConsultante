ALTER TABLE household ADD COLUMN archived_at TIMESTAMPTZ;

CREATE FUNCTION restore_firebase_budget(owner_uid TEXT, budget_id UUID, expected_archived_at TIMESTAMPTZ)
RETURNS BOOLEAN LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    PERFORM pg_advisory_xact_lock_shared(hashtextextended('profile:' || owner_uid, 0));
    PERFORM pg_advisory_xact_lock(hashtextextended('profile-budget:' || budget_id, 0));

    UPDATE household h SET archived_at = NULL
    WHERE h.id = budget_id AND h.archived_at = expected_archived_at
      AND EXISTS (
          SELECT 1 FROM firebase_budget_access b
          JOIN member m ON m.household_id = b.household_id AND m.id = b.member_id
          WHERE b.household_id = h.id AND b.firebase_uid = owner_uid
            AND m.role = 'OWNER' AND m.revoked_at IS NULL
      );
    RETURN FOUND;
END;
$$;

REVOKE EXECUTE ON FUNCTION restore_firebase_budget(TEXT, UUID, TIMESTAMPTZ) FROM PUBLIC;
