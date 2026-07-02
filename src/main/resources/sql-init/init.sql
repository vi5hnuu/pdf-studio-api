-- ---------------------------------------------------------------------------
--  pdf-studio-api — credit system schema (MySQL)
--
--  Identity lives in the standalone auth service; here every row is keyed by the auth
--  userId (JWT subject). JPA `ddl-auto=update` also maintains these; this file is the
--  authoritative definition. Incremental changes go under sql-init/migrations/.
--
--  Tables: credit_accounts, tool_credit_costs, credit_ledger, purchase_audit_log.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS credit_accounts (
    user_id               VARCHAR(40) NOT NULL,               -- auth userId (JWT sub)
    balance               INT         NOT NULL DEFAULT 0,
    last_daily_claim_date DATE        DEFAULT NULL,           -- UTC date of last daily claim
    created_at            DATETIME(6) NOT NULL,
    updated_at            DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tool_credit_costs (
    tool_id          VARCHAR(64) NOT NULL,                    -- endpoint suffix, e.g. compress-pdf
    base_credits     INT         NOT NULL DEFAULT 0,          -- 0 = free
    size_unit        VARCHAR(16) NOT NULL DEFAULT 'NONE',     -- NONE | BYTES | PAGES
    credits_per_unit INT         NOT NULL DEFAULT 0,
    unit_size        BIGINT      NOT NULL DEFAULT 1,
    is_active        BOOLEAN     NOT NULL DEFAULT TRUE,
    PRIMARY KEY (tool_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS credit_ledger (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    user_id         VARCHAR(40) NOT NULL,
    tool_id         VARCHAR(64) DEFAULT NULL,                 -- set for DEBIT rows
    delta           INT         NOT NULL,                     -- signed change
    reason          VARCHAR(16) NOT NULL,                     -- WELCOME|PURCHASE|DEBIT|REWARDED_AD|DAILY|REVOKE
    idempotency_key VARCHAR(100) DEFAULT NULL,
    balance_after   INT         NOT NULL,
    ip              VARCHAR(64) DEFAULT NULL,
    created_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ledger_user_idem (user_id, idempotency_key),
    KEY idx_ledger_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS purchase_audit_log (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    user_id         VARCHAR(40) NOT NULL,
    purchase_token  TEXT        NOT NULL,
    order_id        VARCHAR(128) DEFAULT NULL,
    product_id      VARCHAR(100) NOT NULL,
    status          VARCHAR(30) NOT NULL,                     -- GRANTED | VERIFICATION_FAILED | CREDITS_REVOKED
    credits_granted INT         DEFAULT NULL,
    ip              VARCHAR(64) DEFAULT NULL,
    created_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_pal_token_status (purchase_token(255), status),
    KEY idx_pal_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
