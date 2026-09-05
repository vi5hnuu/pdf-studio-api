-- ---------------------------------------------------------------------------
--  Replace the prefix-based purchase-token uniqueness with a hash key.
--
--  purchase_token was TEXT with UNIQUE (purchase_token(255), status). A Google Play
--  token is longer than 255 characters, so two distinct purchases sharing a prefix
--  collided and the second — a real, paid purchase — was rejected as already redeemed.
--  A SHA-256 hex digest is fixed-width, indexes exactly, and cannot collide in practice.
-- ---------------------------------------------------------------------------

ALTER TABLE purchase_audit_log
    ADD COLUMN token_hash CHAR(64) NULL AFTER purchase_token;

-- Backfill existing rows. MySQL's SHA2 matches the application's SHA-256 hex digest.
UPDATE purchase_audit_log
SET token_hash = SHA2(purchase_token, 256)
WHERE token_hash IS NULL;

ALTER TABLE purchase_audit_log
    DROP INDEX uq_pal_token_status;

ALTER TABLE purchase_audit_log
    MODIFY COLUMN purchase_token VARCHAR(512) NOT NULL,
    MODIFY COLUMN token_hash CHAR(64) NOT NULL,
    ADD UNIQUE KEY uq_pal_token_hash_status (token_hash, status);
