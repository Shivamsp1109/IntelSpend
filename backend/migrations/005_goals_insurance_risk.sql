-- 005 — Goals given real terms, plus protection and risk.
--
-- Three changes that let the engine stop guessing at what a goal means.
--
-- `amount_basis` is the important one. "₹20,00,000 for a car in three years" is
-- ambiguous in a way nobody notices until a projection is wrong: it may mean
-- today's price, which has to be inflated to what the car will cost in 2029, or
-- the figure the user already worked out for 2029, which must not be inflated
-- again. Applying inflation to the second overstates the target by years of
-- compounding; failing to apply it to the first understates it by the same.
-- There is no safe default, so the user is asked, and the engine only inflates
-- where they said today's money.
--
-- `currency` was simply absent, which made a goal in a currency other than the
-- account default unrepresentable — the amount was stored and silently assumed
-- to be rupees.
--
-- `monthly_contribution` already exists and already means the figure the user
-- committed to. It is deliberately left as the only contribution column: any
-- amount the engine works out is a suggestion computed at read time and returned
-- alongside, never written back over what they chose.

ALTER TABLE goals ADD COLUMN currency VARCHAR(10) NOT NULL DEFAULT 'INR';

-- Which of the three readings the target amount is in.
ALTER TABLE goals ADD COLUMN amount_basis
    ENUM('TODAYS_MONEY', 'NOMINAL_FUTURE', 'MANUALLY_FIXED')
    NOT NULL DEFAULT 'TODAYS_MONEY';

-- Which goal gives way when they cannot all be funded. Ranked rather than
-- free text so the recommendation layer can order them deterministically.
ALTER TABLE goals ADD COLUMN priority
    ENUM('ESSENTIAL', 'IMPORTANT', 'NICE_TO_HAVE')
    NOT NULL DEFAULT 'IMPORTANT';

-- What can move if the goal does not fit: the date, the amount, both, or
-- nothing. A school fee due in April is not negotiable; a holiday is.
ALTER TABLE goals ADD COLUMN flexibility
    ENUM('DATE_FLEXIBLE', 'AMOUNT_FLEXIBLE', 'BOTH_FLEXIBLE', 'FIXED')
    NOT NULL DEFAULT 'BOTH_FLEXIBLE';

-- Mirrors the recurring table's lifecycle. A paused goal stops competing for
-- the surplus without being deleted, so the user does not lose the record.
ALTER TABLE goals ADD COLUMN status
    ENUM('ACTIVE', 'PAUSED', 'ABANDONED', 'ACHIEVED')
    NOT NULL DEFAULT 'ACTIVE';

-- Free text naming where the money for this is expected to come from, for the
-- user's own reference. Never parsed or matched on.
ALTER TABLE goals ADD COLUMN funding_source VARCHAR(160) DEFAULT NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- Insurance.
--
-- Cover is the one part of a financial picture where absence is the finding. A
-- household with no life cover and dependants has a gap worth naming, and it
-- cannot be seen from transactions — a premium leaving the account says a policy
-- exists, not what it would pay out.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS insurance_policies (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    uid               VARCHAR(128) NOT NULL,
    local_id          INT NOT NULL,

    policy_type       ENUM('TERM_LIFE', 'WHOLE_LIFE', 'ENDOWMENT', 'ULIP',
                           'HEALTH', 'CRITICAL_ILLNESS', 'PERSONAL_ACCIDENT',
                           'MOTOR', 'HOME', 'TRAVEL', 'OTHER')
                      NOT NULL DEFAULT 'OTHER',
    provider          VARCHAR(160) DEFAULT NULL,
    label             VARCHAR(255) NOT NULL,

    -- What it would pay out. The figure the gap analysis actually needs.
    sum_assured       DECIMAL(18, 2) NOT NULL,
    currency          VARCHAR(10) NOT NULL DEFAULT 'INR',

    premium_amount    DECIMAL(18, 2) DEFAULT NULL,
    premium_cadence   ENUM('MONTHLY', 'QUARTERLY', 'HALF_YEARLY', 'YEARLY', 'SINGLE')
                      NOT NULL DEFAULT 'YEARLY',

    -- When cover ends. A lapsed policy is not cover, and this is what lets the
    -- engine say so rather than counting it.
    policy_end_date   BIGINT DEFAULT NULL,

    -- Recorded because an unnamed nominee is a real and common problem that
    -- makes a payout slow or contested. Nullable: unknown is not the same as no.
    nominee_set       TINYINT(1) DEFAULT NULL,

    updated_at        BIGINT NOT NULL,

    UNIQUE KEY unique_policy_per_user (uid, local_id),
    INDEX idx_insurance_type (uid, policy_type),

    CONSTRAINT fk_insurance_user
        FOREIGN KEY (uid) REFERENCES users(uid)
        ON DELETE CASCADE
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Risk profile.
--
-- Three separate columns, never combined into one number. Willingness to accept
-- a loss, the financial ability to absorb one, and the risk a goal actually
-- requires are different things, and a single "risk score" averaging them
-- produces a figure that is true of none. Someone can be perfectly comfortable
-- with volatility and completely unable to afford it.
--
-- `user_confirmed` is a gate, not a flag. A risk profile is never inferred from
-- spending patterns or holdings and quietly applied — it exists only once the
-- user has answered the questions and confirmed the result. Until then the
-- engine reports risk as unknown and refuses anything that depends on it, which
-- is also what a suitability assessment is supposed to mean.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS risk_assessments (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    uid                   VARCHAR(128) NOT NULL,

    risk_tolerance        ENUM('LOW', 'MODERATE', 'HIGH') DEFAULT NULL,
    risk_capacity         ENUM('LOW', 'MODERATE', 'HIGH') DEFAULT NULL,
    risk_need             ENUM('LOW', 'MODERATE', 'HIGH') DEFAULT NULL,

    -- Which set of questions produced this. A reworded questionnaire is a
    -- different instrument, and an old answer set should not be read as though
    -- it answered the new questions.
    questionnaire_version VARCHAR(20) NOT NULL,
    answers_json          JSON DEFAULT NULL,
    assessment_date       BIGINT NOT NULL,

    -- What this assessment could not establish, carried with it.
    limitations           JSON DEFAULT NULL,

    -- Nothing downstream may read the three columns above unless this is true.
    user_confirmed        TINYINT(1) NOT NULL DEFAULT 0,

    updated_at            BIGINT NOT NULL,

    -- One current profile per user; a re-assessment replaces it.
    UNIQUE KEY unique_risk_per_user (uid),

    CONSTRAINT fk_risk_user
        FOREIGN KEY (uid) REFERENCES users(uid)
        ON DELETE CASCADE
);
