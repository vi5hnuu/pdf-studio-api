-- Collapses the `app` / `web` issuer aliases into a single identity namespace
-- and introduces a billing-channel column on the ledger and the purchase audit.
--
-- The alias was needed when the mobile and web tiers had separate auth
-- deployments: the subject id a token carried was unique only within its own
-- deployment, so every per-user record was keyed by `alias:subject` to avoid
-- two different people sharing a credit balance. The deployments have since
-- been collapsed into one, which makes the subject unique on its own and
-- turns the alias prefix into a split: the same ULID would appear once as
-- `app:ULID` and once as `web:ULID`, and the credits a user bought on one
-- tier were invisible to the other.
--
-- This migration (a) rewrites every per-user key to its bare subject and
-- (b) adds a nullable `channel` column so a credit movement's provenance
-- (play, razorpay, admin) is recorded separately from its reason.
--
-- The SQL avoids MySQL-only forms (SUBSTRING_INDEX, CREATE TABLE LIKE,
-- RENAME TABLE) so the Flyway migration runs against H2's MySQL mode too
-- — that is how SchemaValidatesTest keeps the schema honest.

-- ── 1. New column: billing provenance ─────────────────────────────────────
--
-- `reason` already distinguishes PURCHASE from DEBIT from REWARDED_AD, but
-- it does not say which payment provider a purchase came from, and reporting
-- cannot infer it: a future Razorpay purchase would reuse `reason=PURCHASE`
-- and only differ in channel. Added as nullable because non-billing flows
-- (daily, rewarded_ad, transfer, debit) do not have a channel.

ALTER TABLE credit_ledger
    ADD COLUMN channel VARCHAR(16) NULL;

ALTER TABLE purchase_audit_logs
    ADD COLUMN channel VARCHAR(16) NULL;

-- Every historical PURCHASE/REVOKE row went through Google Play, which is
-- the only billing provider integrated to date. Backfill accordingly.
UPDATE credit_ledger
   SET channel = 'play'
 WHERE reason IN ('PURCHASE', 'REVOKE')
   AND channel IS NULL;

UPDATE purchase_audit_logs
   SET channel = 'play'
 WHERE channel IS NULL;

-- ── 2. Disambiguate TRANSFER rows before the prefix strip ─────────────────
--
-- A guest-balance transfer writes two ledger rows that share one idempotency
-- key: the debit on the source and the credit on the target. Today those
-- rows have different user_ids (alias-prefixed), so (user_id, idempotency_key)
-- is unique per side. After the strip both sides share the same ULID, so the
-- `uq_ledger_user_idem` index would reject one. Tag the source row with
-- ":out" so the two keys stay distinct. The runtime idempotency check in
-- CreditLedgerWriter.transferGuestBalance reads the TARGET side only, which
-- is unchanged, so replay prevention is preserved.

UPDATE credit_ledger
   SET idempotency_key = CONCAT(idempotency_key, ':out')
 WHERE reason = 'TRANSFER'
   AND delta < 0
   AND idempotency_key IS NOT NULL
   AND idempotency_key NOT LIKE '%:out';

-- ── 3. Merge duplicate credit_accounts rows per ULID ──────────────────────
--
-- A user who bought credits on the app tier (`app:ULID`) and on the web tier
-- (`web:ULID`) has two rows today. The two must become one.
--   balance:               summed — the user owns the total of both
--   ad_free:               MAX — ad-free on either side keeps them ad-free
--   last_daily_claim_date: MAX — the more recent claim wins (a user who
--                          claimed on one side today cannot claim again
--                          today on the merged account)
--   created_at:            MIN — the oldest signup wins
--   updated_at:            MAX — the most recent activity wins
--
-- Rebuild-and-swap is the simplest shape that stays correct under a PRIMARY
-- KEY that is about to change. The DDL below mirrors V1's credit_accounts
-- schema plus V2's ad_free column, so the merged table is identical to the
-- entity Hibernate validates against.

CREATE TABLE credit_accounts_new (
    user_id               VARCHAR(64) NOT NULL,
    balance               INT         NOT NULL DEFAULT 0,
    last_daily_claim_date DATE        DEFAULT NULL,
    created_at            DATETIME(6) NOT NULL,
    updated_at            DATETIME(6) NOT NULL,
    ad_free               BOOLEAN     NOT NULL DEFAULT FALSE,
    PRIMARY KEY (user_id)
);

INSERT INTO credit_accounts_new
       (user_id, balance, last_daily_claim_date, created_at, updated_at, ad_free)
SELECT CASE WHEN POSITION(':' IN user_id) > 0
            THEN SUBSTRING(user_id FROM POSITION(':' IN user_id) + 1)
            ELSE user_id END AS user_id,
       SUM(balance),
       MAX(last_daily_claim_date),
       MIN(created_at),
       MAX(updated_at),
       MAX(ad_free)
  FROM credit_accounts
 GROUP BY CASE WHEN POSITION(':' IN user_id) > 0
               THEN SUBSTRING(user_id FROM POSITION(':' IN user_id) + 1)
               ELSE user_id END;

DROP TABLE credit_accounts;
ALTER TABLE credit_accounts_new RENAME TO credit_accounts;

-- ── 4. Strip the alias prefix on the per-user audit tables ────────────────
--
-- Ledger and audit rows stay row-for-row (they are append-only history), but
-- their user_id column drops the `alias:` namespace.

UPDATE credit_ledger
   SET user_id = SUBSTRING(user_id FROM POSITION(':' IN user_id) + 1)
 WHERE user_id LIKE '%:%';

UPDATE purchase_audit_logs
   SET user_id = SUBSTRING(user_id FROM POSITION(':' IN user_id) + 1)
 WHERE user_id LIKE '%:%';
