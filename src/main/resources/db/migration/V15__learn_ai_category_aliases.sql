ALTER TABLE category_rule DROP CONSTRAINT category_rule_keyword_check;
ALTER TABLE category_rule ADD CONSTRAINT category_rule_keyword_check CHECK (
    keyword ~ '^[a-zа-яё]+(-[a-zа-яё]+)*( [a-zа-яё]+(-[a-zа-яё]+)*)*$'
    AND length(keyword) <= 100
);

ALTER TABLE transaction_draft ADD COLUMN recognized_by_ai BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE transaction_draft ADD COLUMN category_alias TEXT CHECK (
    category_alias IS NULL OR (
        category_alias ~ '^[a-zа-яё]+(-[a-zа-яё]+)*( [a-zа-яё]+(-[a-zа-яё]+)*)*$'
        AND length(category_alias) <= 100
    )
);
ALTER TABLE transaction_draft ADD CONSTRAINT transaction_draft_ai_alias_check CHECK (
    (NOT recognized_by_ai OR amount IS NOT NULL)
    AND (category_alias IS NULL OR recognized_by_ai)
);
