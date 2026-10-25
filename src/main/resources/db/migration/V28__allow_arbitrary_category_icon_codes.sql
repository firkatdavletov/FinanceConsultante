-- Icon codes are opaque client identifiers. New artwork must not require a server release.
ALTER TABLE category DROP CONSTRAINT category_icon_code_check;
