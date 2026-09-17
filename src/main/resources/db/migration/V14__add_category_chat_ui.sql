DO $$
DECLARE constraint_name TEXT;
BEGIN
    FOR constraint_name IN SELECT conname FROM pg_constraint
        WHERE conrelid = 'category'::regclass
          AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%dictionary_key%'
    LOOP
        EXECUTE format('ALTER TABLE category DROP CONSTRAINT %I', constraint_name);
    END LOOP;
END $$;

ALTER TABLE category ADD CHECK (dictionary_key IN ('GROCERIES', 'HEALTH', 'SALARY', 'OTHER'));
ALTER TABLE category ADD COLUMN icon_code TEXT NOT NULL DEFAULT 'OTHER'
    CHECK (icon_code IN (
        'FOOD', 'TRANSPORT', 'HOME', 'CLOTHES', 'HEALTH', 'ENTERTAINMENT',
        'SPORT', 'EDUCATION', 'TRAVEL', 'PETS', 'INCOME', 'OTHER'
    ));
UPDATE category SET icon_code = CASE dictionary_key
    WHEN 'GROCERIES' THEN 'FOOD'
    WHEN 'HEALTH' THEN 'HEALTH'
    WHEN 'SALARY' THEN 'INCOME'
    ELSE icon_code
END;

CREATE TABLE telegram_category_dialog (
    owner_id BIGINT PRIMARY KEY CHECK (owner_id > 0),
    step TEXT NOT NULL CHECK (step IN ('ADD_NAME', 'ADD_ICON', 'RENAME')),
    category_id UUID REFERENCES category(id),
    pending_name TEXT CHECK (pending_name IS NULL OR length(pending_name) BETWEEN 1 AND 50),
    screen_message_id BIGINT NOT NULL CHECK (screen_message_id > 0),
    CHECK (
        (step = 'ADD_NAME' AND category_id IS NULL AND pending_name IS NULL)
        OR (step = 'ADD_ICON' AND category_id IS NULL AND pending_name IS NOT NULL)
        OR (step = 'RENAME' AND category_id IS NOT NULL AND pending_name IS NULL)
    )
);

ALTER TABLE telegram_outbox ADD COLUMN delivery_method TEXT NOT NULL DEFAULT 'SEND'
    CHECK (delivery_method IN ('SEND', 'EDIT'));
ALTER TABLE telegram_outbox ADD COLUMN target_message_id BIGINT CHECK (target_message_id > 0);
ALTER TABLE telegram_outbox ADD CHECK (
    (delivery_method = 'EDIT' AND target_message_id IS NOT NULL)
    OR (delivery_method = 'SEND' AND target_message_id IS NULL)
);
