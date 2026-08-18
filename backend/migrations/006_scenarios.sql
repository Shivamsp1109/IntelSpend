-- 006 — Scenarios and the assumptions behind them.
--
-- Every projection this engine makes rests on assumptions, and until now those
-- lived as constants in code. That was defensible while there was one of them
-- and it was labelled; it stops being defensible the moment a user is shown a
-- figure and asks what it assumed.
--
-- So assumptions become data: dated, sourced, versioned, and attached to every
-- projection that used them. Three consequences follow, and all three are the
-- point.
--
-- A projection can be reproduced. A figure computed in August under one set of
-- assumptions can be told apart from the same figure computed in November under
-- another, rather than the two silently disagreeing.
--
-- An assumption can be revised without rewriting history. Bumping the expected
-- return on equities creates a new version; runs that used the old one still
-- point at what they actually used.
--
-- And the user can see what the number rests on. "Your goal is reachable" means
-- something different if it assumed 12% a year than if it assumed 6%, and the
-- difference between those is the whole of the answer.

CREATE TABLE IF NOT EXISTS assumption_sets (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,

    -- Null for the system defaults every user shares. A user-specific override
    -- carries their uid, so one person's changed expectations never move
    -- anybody else's figures.
    uid                 VARCHAR(128) DEFAULT NULL,

    scenario_type       ENUM('CONSERVATIVE', 'BASE', 'OPTIMISTIC') NOT NULL,

    -- What the assumption is about. An expected return is a property of an
    -- asset class over a horizon, not of a scenario alone: equities over twenty
    -- years and equities over two are different propositions, and one number
    -- covering both would be wrong for at least one of them.
    asset_class         ENUM('CASH', 'DEBT', 'EQUITY', 'GOLD', 'REAL_ESTATE', 'BLENDED', 'NONE')
                        NOT NULL DEFAULT 'NONE',
    time_horizon_band   ENUM('SHORT', 'MEDIUM', 'LONG', 'ANY') NOT NULL DEFAULT 'ANY',

    -- Inflation and tax differ by country; a rate assumed for one jurisdiction
    -- must not be silently applied to another.
    jurisdiction        VARCHAR(8) NOT NULL DEFAULT 'IN',
    currency            VARCHAR(10) NOT NULL DEFAULT 'INR',

    -- Annual rates as percentages, e.g. 6.5000. Nullable because an assumption
    -- set may speak to only some of them.
    inflation_rate      DECIMAL(7, 4) DEFAULT NULL,
    expected_return     DECIMAL(7, 4) DEFAULT NULL,
    income_growth_rate  DECIMAL(7, 4) DEFAULT NULL,
    expense_growth_rate DECIMAL(7, 4) DEFAULT NULL,

    -- Where the figure came from, in plain words. An assumption whose origin
    -- nobody can state is one nobody can defend.
    source              VARCHAR(500) NOT NULL,
    methodology         VARCHAR(500) DEFAULT NULL,

    -- When it started applying and when it stopped. Superseding a set closes it
    -- rather than editing it, so a run from last quarter still resolves to the
    -- figures it actually used.
    effective_from      BIGINT NOT NULL,
    effective_to        BIGINT DEFAULT NULL,

    version             VARCHAR(20) NOT NULL,
    -- Whether this has been reviewed. A draft must never reach a projection the
    -- user is shown.
    approval_status     ENUM('DRAFT', 'APPROVED', 'SUPERSEDED') NOT NULL DEFAULT 'DRAFT',

    updated_at          BIGINT NOT NULL,

    -- The lookup every projection performs.
    INDEX idx_assumption_lookup
        (scenario_type, asset_class, time_horizon_band, jurisdiction, approval_status),
    INDEX idx_assumption_user (uid),

    CONSTRAINT fk_assumption_user
        FOREIGN KEY (uid) REFERENCES users(uid)
        ON DELETE CASCADE
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Scenario runs.
--
-- One row per projection produced, holding what went in, what came out, and
-- which assumptions and which snapshot were used.
--
-- `inputs_hash` is what makes a run checkable: the same inputs under the same
-- assumptions must give the same outputs, and a stored hash turns that from a
-- hope into something a test can assert.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS scenario_runs (
    run_id            CHAR(36) PRIMARY KEY,
    uid               VARCHAR(128) NOT NULL,

    -- The state the projection was made from. Without it a run cannot be
    -- explained later, only re-derived against data that has since moved.
    snapshot_id       CHAR(36) DEFAULT NULL,
    assumption_set_id BIGINT DEFAULT NULL,

    scenario_type     ENUM('CONSERVATIVE', 'BASE', 'OPTIMISTIC') NOT NULL,
    subject_type      ENUM('GOAL', 'DEBT_PAYOFF', 'PORTFOLIO', 'CASH_FLOW') NOT NULL,
    -- Which goal or loan this was about, where that applies.
    subject_local_id  INT DEFAULT NULL,

    inputs_hash       CHAR(64) NOT NULL,
    outputs_json      JSON NOT NULL,

    engine_version    VARCHAR(20) NOT NULL,
    run_at            BIGINT NOT NULL,

    INDEX idx_scenario_run_user (uid, run_at DESC),
    INDEX idx_scenario_run_snapshot (snapshot_id),

    CONSTRAINT fk_scenario_run_user
        FOREIGN KEY (uid) REFERENCES users(uid)
        ON DELETE CASCADE,
    -- SET NULL rather than CASCADE: pruning old snapshots should not delete the
    -- record that a projection was made, only the detail of what it saw.
    CONSTRAINT fk_scenario_run_snapshot
        FOREIGN KEY (snapshot_id) REFERENCES financial_snapshots(snapshot_id)
        ON DELETE SET NULL,
    CONSTRAINT fk_scenario_run_assumptions
        FOREIGN KEY (assumption_set_id) REFERENCES assumption_sets(id)
        ON DELETE SET NULL
);

-- ─────────────────────────────────────────────────────────────────────────────
-- The system assumption sets.
--
-- Seeded here rather than hardcoded in engine code, so they can be inspected,
-- versioned and revised without a deploy — and so every figure built on them can
-- point at a row that says where the number came from.
--
-- These are planning assumptions, not forecasts. Each will be wrong in any given
-- year; the reason there are three is that the spread between them is more
-- honest than any one of them alone.
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO assumption_sets (
    uid, scenario_type, asset_class, time_horizon_band, jurisdiction, currency,
    inflation_rate, expected_return, income_growth_rate, expense_growth_rate,
    source, methodology, effective_from, version, approval_status, updated_at
) VALUES
-- Inflation, per scenario. India's headline CPI has spent most of the last
-- decade between 4% and 7%, which is the range these bracket.
(NULL, 'CONSERVATIVE', 'NONE', 'ANY', 'IN', 'INR', 7.5000, NULL, 3.0000, 7.5000,
 'Upper end of India''s recent CPI range, with income growth assumed to lag it.',
 'Chosen to stress a plan rather than to describe the likeliest year.',
 UNIX_TIMESTAMP() * 1000, '1.0.0', 'APPROVED', UNIX_TIMESTAMP() * 1000),

(NULL, 'BASE', 'NONE', 'ANY', 'IN', 'INR', 6.0000, NULL, 6.0000, 6.0000,
 'Near the midpoint of India''s recent CPI range.',
 'Central planning assumption. Will be wrong in any particular year.',
 UNIX_TIMESTAMP() * 1000, '1.0.0', 'APPROVED', UNIX_TIMESTAMP() * 1000),

(NULL, 'OPTIMISTIC', 'NONE', 'ANY', 'IN', 'INR', 4.5000, NULL, 8.0000, 4.5000,
 'Lower end of India''s recent CPI range, with income growth assumed to outpace it.',
 'Illustrates a favourable run. Not a target and not a forecast.',
 UNIX_TIMESTAMP() * 1000, '1.0.0', 'APPROVED', UNIX_TIMESTAMP() * 1000),

-- Cash, at every horizon. Deliberately near-zero in real terms: money in a
-- savings account is what a near-term goal should be modelled on, and modelling
-- it as if it were invested is exactly the error the engine guards against.
(NULL, 'CONSERVATIVE', 'CASH', 'ANY', 'IN', 'INR', NULL, 3.0000, NULL, NULL,
 'Typical Indian savings-account rate.', 'Assumes no market exposure.',
 UNIX_TIMESTAMP() * 1000, '1.0.0', 'APPROVED', UNIX_TIMESTAMP() * 1000),
(NULL, 'BASE', 'CASH', 'ANY', 'IN', 'INR', NULL, 4.0000, NULL, NULL,
 'Typical Indian savings-account rate.', 'Assumes no market exposure.',
 UNIX_TIMESTAMP() * 1000, '1.0.0', 'APPROVED', UNIX_TIMESTAMP() * 1000),
(NULL, 'OPTIMISTIC', 'CASH', 'ANY', 'IN', 'INR', NULL, 6.0000, NULL, NULL,
 'Upper end of Indian liquid-fund and sweep-account returns.',
 'Assumes no market exposure.',
 UNIX_TIMESTAMP() * 1000, '1.0.0', 'APPROVED', UNIX_TIMESTAMP() * 1000),

-- Debt instruments.
(NULL, 'CONSERVATIVE', 'DEBT', 'ANY', 'IN', 'INR', NULL, 5.0000, NULL, NULL,
 'Below typical Indian debt-fund and fixed-deposit returns.',
 'Stress assumption; returns are not guaranteed.',
 UNIX_TIMESTAMP() * 1000, '1.0.0', 'APPROVED', UNIX_TIMESTAMP() * 1000),
(NULL, 'BASE', 'DEBT', 'ANY', 'IN', 'INR', NULL, 7.0000, NULL, NULL,
 'Around typical Indian debt-fund and fixed-deposit returns.',
 'Returns are not guaranteed.',
 UNIX_TIMESTAMP() * 1000, '1.0.0', 'APPROVED', UNIX_TIMESTAMP() * 1000),
(NULL, 'OPTIMISTIC', 'DEBT', 'ANY', 'IN', 'INR', NULL, 8.5000, NULL, NULL,
 'Upper end of recent Indian debt returns.', 'Returns are not guaranteed.',
 UNIX_TIMESTAMP() * 1000, '1.0.0', 'APPROVED', UNIX_TIMESTAMP() * 1000),

-- Equities, split by horizon. The short band is deliberately pessimistic and
-- wide: equity over one or two years is not a return assumption, it is a coin
-- toss, and the engine will not use this band for a near-term goal anyway.
(NULL, 'CONSERVATIVE', 'EQUITY', 'SHORT', 'IN', 'INR', NULL, -10.0000, NULL, NULL,
 'A losing short run, which equity markets regularly deliver.',
 'Short-horizon equity is not modelled as a positive return.',
 UNIX_TIMESTAMP() * 1000, '1.0.0', 'APPROVED', UNIX_TIMESTAMP() * 1000),
(NULL, 'BASE', 'EQUITY', 'SHORT', 'IN', 'INR', NULL, 0.0000, NULL, NULL,
 'Flat, because a two-year equity return is not predictable in either direction.',
 'Short-horizon equity is not modelled as a positive return.',
 UNIX_TIMESTAMP() * 1000, '1.0.0', 'APPROVED', UNIX_TIMESTAMP() * 1000),
(NULL, 'OPTIMISTIC', 'EQUITY', 'SHORT', 'IN', 'INR', NULL, 10.0000, NULL, NULL,
 'A favourable short run.', 'Illustrative only; not a target.',
 UNIX_TIMESTAMP() * 1000, '1.0.0', 'APPROVED', UNIX_TIMESTAMP() * 1000),

(NULL, 'CONSERVATIVE', 'EQUITY', 'MEDIUM', 'IN', 'INR', NULL, 6.0000, NULL, NULL,
 'Well below long-run Indian equity averages.', 'Stress assumption.',
 UNIX_TIMESTAMP() * 1000, '1.0.0', 'APPROVED', UNIX_TIMESTAMP() * 1000),
(NULL, 'BASE', 'EQUITY', 'MEDIUM', 'IN', 'INR', NULL, 10.0000, NULL, NULL,
 'Below long-run Indian equity averages, allowing for a shorter holding period.',
 'Returns are not guaranteed and vary widely over shorter periods.',
 UNIX_TIMESTAMP() * 1000, '1.0.0', 'APPROVED', UNIX_TIMESTAMP() * 1000),
(NULL, 'OPTIMISTIC', 'EQUITY', 'MEDIUM', 'IN', 'INR', NULL, 13.0000, NULL, NULL,
 'Around long-run Indian equity averages.', 'Illustrative only; not a target.',
 UNIX_TIMESTAMP() * 1000, '1.0.0', 'APPROVED', UNIX_TIMESTAMP() * 1000),

(NULL, 'CONSERVATIVE', 'EQUITY', 'LONG', 'IN', 'INR', NULL, 8.0000, NULL, NULL,
 'Materially below long-run Indian equity averages.', 'Stress assumption.',
 UNIX_TIMESTAMP() * 1000, '1.0.0', 'APPROVED', UNIX_TIMESTAMP() * 1000),
(NULL, 'BASE', 'EQUITY', 'LONG', 'IN', 'INR', NULL, 12.0000, NULL, NULL,
 'Near long-run Indian equity averages over multi-decade periods.',
 'Past performance does not indicate future returns.',
 UNIX_TIMESTAMP() * 1000, '1.0.0', 'APPROVED', UNIX_TIMESTAMP() * 1000),
(NULL, 'OPTIMISTIC', 'EQUITY', 'LONG', 'IN', 'INR', NULL, 15.0000, NULL, NULL,
 'Above long-run Indian equity averages.', 'Illustrative only; not a target.',
 UNIX_TIMESTAMP() * 1000, '1.0.0', 'APPROVED', UNIX_TIMESTAMP() * 1000),

-- A blended holding, for a portfolio whose mix is not broken down.
(NULL, 'CONSERVATIVE', 'BLENDED', 'ANY', 'IN', 'INR', NULL, 5.0000, NULL, NULL,
 'A cautious blend of debt and equity.', 'Stress assumption.',
 UNIX_TIMESTAMP() * 1000, '1.0.0', 'APPROVED', UNIX_TIMESTAMP() * 1000),
(NULL, 'BASE', 'BLENDED', 'ANY', 'IN', 'INR', NULL, 8.0000, NULL, NULL,
 'A balanced blend of debt and equity.', 'Returns are not guaranteed.',
 UNIX_TIMESTAMP() * 1000, '1.0.0', 'APPROVED', UNIX_TIMESTAMP() * 1000),
(NULL, 'OPTIMISTIC', 'BLENDED', 'ANY', 'IN', 'INR', NULL, 11.0000, NULL, NULL,
 'A growth-leaning blend.', 'Illustrative only; not a target.',
 UNIX_TIMESTAMP() * 1000, '1.0.0', 'APPROVED', UNIX_TIMESTAMP() * 1000);
