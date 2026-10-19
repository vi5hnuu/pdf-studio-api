-- Server-owned configuration the app reads at launch: which builds are still supported, and how
-- often to ask for a rating.
--
-- A table rather than properties, following tool_credit_costs: both are operational knobs that
-- want changing without a redeploy, and a forced update is precisely the situation where
-- redeploying to change a number is the last thing you want to be doing.
--
-- Gating is on the build number, not the version string. It is an integer and monotonic
-- (4.0.0+21 -> 21); comparing semver strings invites a "4.0.10 < 4.0.9" bug for no benefit. The
-- version string is carried for display only.
--
-- min_build starts at 0 on purpose: shipping this enforces nothing until someone deliberately
-- raises it.

CREATE TABLE IF NOT EXISTS app_release_config (
    platform                     VARCHAR(16)  NOT NULL,          -- ANDROID | IOS
    min_build                    INT          NOT NULL DEFAULT 0,  -- below this the app blocks
    recommended_build            INT          NOT NULL DEFAULT 0,  -- below this the app nudges
    update_message               VARCHAR(512) DEFAULT NULL,       -- optional copy for the gate
    store_url                    VARCHAR(512) DEFAULT NULL,       -- where the Update button goes
    -- Rating thresholds, here so they can be retuned without a release.
    rating_min_successes         INT          NOT NULL DEFAULT 3,
    rating_min_days_installed    INT          NOT NULL DEFAULT 3,
    rating_min_days_between_asks INT          NOT NULL DEFAULT 60,
    updated_at                   DATETIME(6)  NOT NULL,
    PRIMARY KEY (platform)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
