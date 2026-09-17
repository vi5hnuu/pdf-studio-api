-- Server-owned ad-free entitlement.
--
-- The app previously held this as a local preference, so switching ads off was a matter of
-- editing shared preferences. The server now owns it and returns it on /credits/balance; the
-- client caches the value for offline use but never decides it.
ALTER TABLE credit_accounts
    ADD COLUMN ad_free BOOLEAN NOT NULL DEFAULT FALSE;
