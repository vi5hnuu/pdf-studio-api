-- ---------------------------------------------------------------------------
--  pdf-studio-api — credit system schema (MySQL)
--
--  Identity lives in the auth service(s); every row here is keyed by the namespaced
--  auth id "<issuer alias>:<sub>" (see PrincipalKey). The alias matters because the
--  mobile app and the web tier are served by separate auth deployments with separate
--  databases, so the same subject can legitimately exist in both — keying on the bare
--  subject would merge two unrelated people's balances.
--
--  Applied by Flyway; Hibernate only validates against it (ddl-auto=validate). Later
--  changes go in new V<n>__ files alongside this one.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS credit_accounts (
    user_id               VARCHAR(64) NOT NULL,               -- "<alias>:<sub>"
    balance               INT         NOT NULL DEFAULT 0,
    last_daily_claim_date DATE        DEFAULT NULL,           -- UTC date of last daily claim
    created_at            DATETIME(6) NOT NULL,
    updated_at            DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tool_credit_costs (
    tool_id          VARCHAR(64) NOT NULL,                    -- endpoint suffix, e.g. compress-pdf
    base_credits     INT         NOT NULL DEFAULT 0,          -- 0 = free
    size_unit        VARCHAR(16) NOT NULL DEFAULT 'NONE',     -- NONE | BYTES
    credits_per_unit INT         NOT NULL DEFAULT 0,
    unit_size        BIGINT      NOT NULL DEFAULT 1,          -- block size in bytes
    is_active        BOOLEAN     NOT NULL DEFAULT TRUE,
    PRIMARY KEY (tool_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS credit_ledger (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    user_id         VARCHAR(64) NOT NULL,
    tool_id         VARCHAR(64) DEFAULT NULL,                 -- set for DEBIT rows
    delta           INT         NOT NULL,                     -- signed change
    reason          VARCHAR(16) NOT NULL,                     -- WELCOME|PURCHASE|DEBIT|REWARDED_AD|DAILY|REVOKE|TRANSFER
    idempotency_key VARCHAR(100) DEFAULT NULL,
    balance_after   INT         NOT NULL,
    ip              VARCHAR(64) DEFAULT NULL,
    created_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    -- Makes a retried tool request safe: the same key is charged exactly once.
    UNIQUE KEY uq_ledger_user_idem (user_id, idempotency_key),
    KEY idx_ledger_user (user_id),
    -- The rewarded-ad daily cap filters by reason and time as well as user.
    KEY idx_ledger_user_reason_time (user_id, reason, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS purchase_audit_logs (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         VARCHAR(64)  NOT NULL,
    purchase_token  VARCHAR(512) NOT NULL,
    -- Uniqueness is on a SHA-256 of the token, not the token: MySQL cannot index a long
    -- value without a prefix, and a Play token exceeds any workable prefix length — two
    -- purchases sharing that prefix would collide and the second would be refused as
    -- already redeemed. A fixed-width digest indexes exactly.
    token_hash      VARCHAR(64)  NOT NULL,
    order_id        VARCHAR(128) DEFAULT NULL,
    product_id      VARCHAR(100) NOT NULL,
    status          VARCHAR(30)  NOT NULL,                    -- GRANTED | VERIFICATION_FAILED | CREDITS_REVOKED
    credits_granted INT          DEFAULT NULL,
    ip              VARCHAR(64)  DEFAULT NULL,
    created_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_pal_token_hash_status (token_hash, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Free grants are rationed per client IP as well as per account: per-account limits only
-- deter abuse when accounts are costly, and the web tier mints guest accounts on demand.
-- The IP is stored only as a salted hash.
CREATE TABLE IF NOT EXISTS credit_ip_grants (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    ip_hash     VARCHAR(64) NOT NULL,
    grant_kind  VARCHAR(16) NOT NULL,                         -- WELCOME | DAILY
    grant_date  DATE        NOT NULL,                         -- UTC day the counter applies to
    grant_count INT         NOT NULL DEFAULT 0,
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_grant_ip_kind_day (ip_hash, grant_kind, grant_date),
    KEY idx_grant_ip_date (grant_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
