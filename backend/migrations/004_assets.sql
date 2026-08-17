-- 004 — Assets.
--
-- What the user owns, so net worth and emergency coverage stop being guesses.
--
-- Two design decisions are worth stating, because both are easy to get wrong in
-- ways that look reasonable.
--
-- First, there is deliberately **no expected_return or risk_level column**. An
-- asset is "a mutual fund holding worth ₹4,00,000 as of 12 August" — that is an
-- observed fact. "A mutual fund that returns 11%" is not a fact about the row;
-- it is a dated modelling assumption about an asset class, it changes without
-- the holding changing, and storing it here would let a projection quietly
-- present it as something the user told us. Expected returns therefore live in
-- versioned assumption sets, keyed by asset class, and are applied at read time.
--
-- Second, liquidity is its own field rather than inferred from the type. A fixed
-- deposit and a five-year tax-saving deposit are both FIXED_DEPOSIT, and only
-- one of them can be reached in an emergency. Inferring it from the type would
-- count locked money as an emergency reserve, which is precisely the error that
-- makes a reserve figure dangerous rather than merely wrong.

CREATE TABLE IF NOT EXISTS assets (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    uid                 VARCHAR(128) NOT NULL,
    local_id            INT NOT NULL,

    asset_type          ENUM(
                            'CASH', 'BANK_ACCOUNT', 'FIXED_DEPOSIT', 'RECURRING_DEPOSIT',
                            'MUTUAL_FUND', 'STOCK', 'BOND', 'ETF',
                            'PROVIDENT_FUND', 'PENSION', 'NPS',
                            'GOLD', 'REAL_ESTATE', 'VEHICLE',
                            'INSURANCE_CASH_VALUE', 'CRYPTO', 'LOAN_GIVEN', 'OTHER'
                        ) NOT NULL DEFAULT 'OTHER',
    label               VARCHAR(255) NOT NULL,

    current_value       DECIMAL(18, 2) NOT NULL,
    currency            VARCHAR(10) NOT NULL DEFAULT 'INR',
    -- When that value was true. A holding valued eight months ago says little
    -- about today, and the readiness model downgrades on this rather than
    -- presenting a stale figure as current.
    valuation_date      BIGINT NOT NULL,

    -- Whether the money can actually be reached, which is the question an
    -- emergency reserve asks. Kept apart from asset_type on purpose.
    liquidity_class     ENUM(
                            'LIQUID_CASH',           -- reachable today
                            'LIQUID_INVESTMENT',     -- days, possibly at a loss
                            'ILLIQUID_INVESTMENT',   -- weeks or months
                            'PHYSICAL',              -- must be sold
                            'RETIREMENT_LOCKED'      -- unreachable without penalty
                        ) NOT NULL DEFAULT 'ILLIQUID_INVESTMENT',
    -- A tax-saving deposit is liquid in three years and not before. Until this
    -- date passes the holding is excluded from reserve coverage whatever its
    -- liquidity class says.
    lock_in_until       BIGINT DEFAULT NULL,

    -- Whose it is. Family money the user cannot unilaterally spend must not be
    -- counted towards their own reserve.
    ownership           ENUM('SELF', 'JOINT', 'FAMILY') NOT NULL DEFAULT 'SELF',

    -- How the figure got here, so an assessment can weigh a typed estimate
    -- differently from a confirmed statement balance.
    verification_source ENUM('MANUAL', 'IMPORTED', 'CONFIRMED') NOT NULL DEFAULT 'MANUAL',

    -- Free text: the institution or account this sits in, for the user's own
    -- recognition. Never parsed or matched on.
    account_type        VARCHAR(120) DEFAULT NULL,

    updated_at          BIGINT NOT NULL,

    UNIQUE KEY unique_asset_per_user (uid, local_id),
    INDEX idx_assets_liquidity (uid, liquidity_class),

    CONSTRAINT fk_assets_user
        FOREIGN KEY (uid) REFERENCES users(uid)
        ON DELETE CASCADE
);
