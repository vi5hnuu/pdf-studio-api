-- ---------------------------------------------------------------------------
--  Namespace user ids by auth issuer, and ration free grants per IP.
--
--  The mobile app and the web tier are served by separate auth deployments with
--  separate databases, so each generates user ids independently and the same id can
--  legitimately exist in both. Keying credit accounts on the bare JWT subject would
--  merge two unrelated people's balances the first time those ids coincided.
--  Every per-user key becomes "<issuer alias>:<subject>".
--
--  Existing rows were all minted by the original (app) auth service, so they are
--  prefixed with that alias.
-- ---------------------------------------------------------------------------

-- "app:" plus a 36-character UUID is exactly 40 characters, leaving no headroom;
-- widen before prefixing.
ALTER TABLE credit_accounts   MODIFY COLUMN user_id VARCHAR(64) NOT NULL;
ALTER TABLE credit_ledger     MODIFY COLUMN user_id VARCHAR(64) NOT NULL;
ALTER TABLE purchase_audit_log MODIFY COLUMN user_id VARCHAR(64) NOT NULL;

-- Prefix only rows that are not already namespaced, so re-running is harmless.
UPDATE credit_accounts    SET user_id = CONCAT('app:', user_id) WHERE user_id NOT LIKE '%:%';
UPDATE credit_ledger      SET user_id = CONCAT('app:', user_id) WHERE user_id NOT LIKE '%:%';
UPDATE purchase_audit_log SET user_id = CONCAT('app:', user_id) WHERE user_id NOT LIKE '%:%';

-- Ledger idempotency keys embed the userId for the daily claim ("daily:<userId>:<date>"),
-- so they need the same prefix to stay aligned with the account they belong to.
UPDATE credit_ledger
SET idempotency_key = CONCAT('daily:app:', SUBSTRING(idempotency_key, 7))
WHERE idempotency_key LIKE 'daily:%' AND idempotency_key NOT LIKE 'daily:app:%'
  AND idempotency_key NOT LIKE 'daily:web:%';

UPDATE credit_ledger
SET idempotency_key = CONCAT('welcome:app:', SUBSTRING(idempotency_key, 9))
WHERE idempotency_key LIKE 'welcome:%' AND idempotency_key NOT LIKE 'welcome:app:%'
  AND idempotency_key NOT LIKE 'welcome:web:%';

-- ---------------------------------------------------------------------------
--  Per-IP rationing of free grants.
--
--  Per-account limits only deter abuse when accounts are expensive to obtain; the web
--  tier hands out guest accounts on demand. Counting per IP per day closes that without
--  locking out shared addresses. The IP is stored only as a salted hash.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS credit_grant_ip (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    ip_hash     CHAR(64)    NOT NULL,               -- salted SHA-256 of the client IP
    grant_kind  VARCHAR(16) NOT NULL,               -- WELCOME | DAILY
    grant_date  DATE        NOT NULL,               -- UTC day the counter applies to
    grant_count INT         NOT NULL DEFAULT 0,
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_grant_ip_kind_day (ip_hash, grant_kind, grant_date),
    KEY idx_grant_ip_date (grant_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
