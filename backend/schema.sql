CREATE DATABASE IF NOT EXISTS spendwise;
USE spendwise;

-- ─────────────────────────────────────────────────────────────────────────────
-- Core tables (fresh install gets all columns from the start)
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS users (
    uid                      VARCHAR(128) PRIMARY KEY,
    name                     VARCHAR(100) NOT NULL DEFAULT '',
    email                    VARCHAR(150) NOT NULL DEFAULT '',
    gender                   VARCHAR(30),
    explicit_profile_image_url TEXT,
    google_photo_url         TEXT,
    providers                JSON,
    updated_at               BIGINT NOT NULL
);

-- v2 definition — includes merchant / currency / source from the start
CREATE TABLE IF NOT EXISTS expenses (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    uid          VARCHAR(128) NOT NULL,
    local_id     INT NOT NULL,
    title        VARCHAR(255) NOT NULL,
    amount       DOUBLE NOT NULL,
    category     VARCHAR(100) NOT NULL,
    expense_date BIGINT NOT NULL,
    merchant     VARCHAR(255) DEFAULT NULL,
    currency     VARCHAR(10) NOT NULL DEFAULT 'INR',
    source       VARCHAR(50) NOT NULL DEFAULT 'MANUAL',
    -- Bank or UPI reference (RRN/UTR) when the imported document carried one.
    -- Identifies the same payment across documents, so duplicate detection
    -- survives a reinstall.
    reference    VARCHAR(64) DEFAULT NULL,
    -- 1 when the date was substituted at import because the document showed
    -- none. Duplicate detection must not treat such a date as evidence.
    date_is_assumed TINYINT(1) NOT NULL DEFAULT 0,
    updated_at   BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY unique_user_expense (uid, local_id),
    INDEX idx_expenses_uid_date (uid, expense_date),
    CONSTRAINT fk_expenses_user
        FOREIGN KEY (uid) REFERENCES users(uid)
        ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS incomes (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    uid         VARCHAR(128) NOT NULL,
    local_id    INT NOT NULL,
    title       VARCHAR(255) NOT NULL,
    amount      DOUBLE NOT NULL,
    currency    VARCHAR(10) NOT NULL DEFAULT 'INR',
    -- IncomeSource enum name, e.g. 'SALARY', 'DIVIDENDS', 'MISCELLANEOUS'
    source      VARCHAR(100) NOT NULL,
    -- Free-text note; required when source = 'MISCELLANEOUS'
    note        TEXT DEFAULT NULL,
    income_date BIGINT NOT NULL,
    -- See expenses.reference.
    reference   VARCHAR(64) DEFAULT NULL,
    -- See expenses.date_is_assumed.
    date_is_assumed TINYINT(1) NOT NULL DEFAULT 0,
    updated_at  BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY unique_user_income (uid, local_id),
    INDEX idx_incomes_uid_date (uid, income_date),
    CONSTRAINT fk_incomes_user
        FOREIGN KEY (uid) REFERENCES users(uid)
        ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS goals (
    id                   INT AUTO_INCREMENT PRIMARY KEY,
    uid                  VARCHAR(128) NOT NULL,
    local_id             INT NOT NULL,
    -- GoalType enum name, e.g. 'EMERGENCY_FUND', 'VACATION'
    type                 VARCHAR(50) NOT NULL,
    target_amount        DOUBLE NOT NULL,
    target_date          BIGINT NOT NULL,
    current_saved        DOUBLE NOT NULL DEFAULT 0,
    monthly_contribution DOUBLE NOT NULL DEFAULT 0,
    updated_at           BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY unique_user_goal (uid, local_id),
    CONSTRAINT fk_goals_user
        FOREIGN KEY (uid) REFERENCES users(uid)
        ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS recurring (
    id         INT AUTO_INCREMENT PRIMARY KEY,
    uid        VARCHAR(128) NOT NULL,
    local_id   INT NOT NULL,
    title      VARCHAR(255) NOT NULL,
    amount     DOUBLE NOT NULL,
    -- RecurringCadence enum name, e.g. 'MONTHLY', 'YEARLY'
    cadence    VARCHAR(20) NOT NULL,
    -- RecurringType enum name, e.g. 'FIXED', 'VARIABLE', 'ONE_TIME'
    type       VARCHAR(20) NOT NULL,
    currency   VARCHAR(10) NOT NULL DEFAULT 'INR',
    -- TransactionNature enum name. Decides whether this commitment is
    -- consumption, debt repayment or asset building — rent, an EMI and a monthly
    -- SIP are all "the same amount every month" and must never be summed as one.
    nature     VARCHAR(30) NOT NULL DEFAULT 'Spending',
    -- ExpenseCategory label, e.g. 'Subscriptions'
    category   VARCHAR(100) NOT NULL DEFAULT 'Other',
    -- RecurringSource enum name: 'MANUAL' or 'DETECTED'
    source     VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    -- How many historical payments a detection was inferred from, and how sure
    -- it was. Both are 0 / 1.0 for a hand-entered commitment.
    occurrence_count INT NOT NULL DEFAULT 0,
    confidence DOUBLE NOT NULL DEFAULT 1,
    -- RecurringStatus enum name: 'ACTIVE', 'PAUSED' or 'ENDED'. A paused gym
    -- membership must stop counting and stop reminding without being deleted —
    -- deleting would lose what it costs and let detection re-suggest it.
    status     VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    last_occurrence_date BIGINT DEFAULT NULL,
    next_due_date        BIGINT DEFAULT NULL,
    -- The day of the month this is anchored to. Stored rather than derived: a
    -- commitment due on the 31st is paid on the 28th in February, and projecting
    -- from that date would walk it backwards through the calendar for good.
    due_day_of_month     INT DEFAULT NULL,
    updated_at BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY unique_user_recurring (uid, local_id),
    CONSTRAINT fk_recurring_user
        FOREIGN KEY (uid) REFERENCES users(uid)
        ON DELETE CASCADE
);

-- Patterns the user has said are not commitments.
--
-- Synced so a reinstall does not resurrect every suggestion they already
-- rejected — being asked the same questions again after restoring a backup is
-- the fastest way to make a feature feel broken.
CREATE TABLE IF NOT EXISTS dismissed_recurring_candidates (
    id                        INT AUTO_INCREMENT PRIMARY KEY,
    uid                       VARCHAR(128) NOT NULL,
    signature                 VARCHAR(512) NOT NULL,
    merchant                  VARCHAR(255) NOT NULL,
    currency                  VARCHAR(10) NOT NULL DEFAULT 'INR',
    nature                    VARCHAR(30) NOT NULL DEFAULT 'Spending',
    category                  VARCHAR(100) NOT NULL DEFAULT 'Other',
    cadence                   VARCHAR(20) NOT NULL,
    -- What was rejected, not just who. A dismissal is reconsidered when the
    -- charge changes materially, so the old amount has to be remembered.
    last_seen_amount          DOUBLE NOT NULL DEFAULT 0,
    last_seen_occurrence_date BIGINT NOT NULL DEFAULT 0,
    dismissed_at              BIGINT NOT NULL DEFAULT 0,
    -- Prefix-bounded because MySQL caps an index key at 3072 bytes and a utf8mb4
    -- VARCHAR(512) exceeds it; 191 characters is far more than any real
    -- merchant-plus-category signature needs.
    UNIQUE KEY unique_user_dismissal (uid, signature(191)),
    CONSTRAINT fk_dismissal_user
        FOREIGN KEY (uid) REFERENCES users(uid)
        ON DELETE CASCADE
);

-- Many-to-many: recurring ↔ expenses
CREATE TABLE IF NOT EXISTS recurring_expense_cross_ref (
    uid                VARCHAR(128) NOT NULL,
    recurring_local_id INT NOT NULL,
    expense_local_id   INT NOT NULL,
    PRIMARY KEY (uid, recurring_local_id, expense_local_id),
    INDEX idx_cross_ref_expense (uid, expense_local_id),
    CONSTRAINT fk_cross_ref_recurring
        FOREIGN KEY (uid, recurring_local_id)
        REFERENCES recurring(uid, local_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_cross_ref_expense
        FOREIGN KEY (uid, expense_local_id)
        REFERENCES expenses(uid, local_id)
        ON DELETE CASCADE
);

-- Standing monthly limits per category.
--
-- Named category_budgets rather than budgets because the app already has a
-- different notion of a budget — the home screen compares a whole month's
-- spending against income. That answers "am I living within my means"; this
-- answers "am I spending more on eating out than I meant to".
--
-- The alert columns are deliberately not synced back down on restore: whether a
-- notification has been shown is a fact about a handset, not the account.
CREATE TABLE IF NOT EXISTS category_budgets (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    uid           VARCHAR(128) NOT NULL,
    local_id      INT NOT NULL,
    category      VARCHAR(100) NOT NULL,
    monthly_limit DOUBLE NOT NULL,
    currency      VARCHAR(10) NOT NULL DEFAULT 'INR',
    updated_at    BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY unique_user_category_budget (uid, local_id),
    -- One limit per category per currency, matching the app's own constraint.
    UNIQUE KEY unique_user_category (uid, category, currency),
    CONSTRAINT fk_category_budgets_user
        FOREIGN KEY (uid) REFERENCES users(uid)
        ON DELETE CASCADE
);

-- ─────────────────────────────────────────────────────────────────────────────
-- LLM extraction usage log.
--
-- One row per successful model call. An escalated image writes two rows, one
-- per model, so the cap and the cost total both reflect real spend. Drives the
-- per-user monthly
-- cap and makes spend inspectable without leaving the database:
--   SELECT DATE(FROM_UNIXTIME(created_at/1000)) AS day,
--          COUNT(*) AS calls, SUM(estimated_cost_usd) AS usd
--     FROM llm_usage GROUP BY day ORDER BY day DESC;
--
-- No image or extracted content is stored here — token counts only.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS llm_usage (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    uid                VARCHAR(128) NOT NULL,
    model              VARCHAR(64)  NOT NULL,
    input_tokens       INT          NOT NULL,
    output_tokens      INT          NOT NULL,
    -- 8 decimal places: a single Flash-Lite call costs around $0.0003, so 6
    -- places would round a meaningful share of each row away.
    estimated_cost_usd DECIMAL(12, 8) NOT NULL DEFAULT 0,
    created_at         BIGINT       NOT NULL,
    INDEX idx_llm_usage_uid_time (uid, created_at)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- v1 → v2 upgrade path (ONLY run this block on an existing v1 database)
-- NOTE: ALTER TABLE ... ADD COLUMN IF NOT EXISTS is MariaDB-only.
--       Standard MySQL requires plain ADD COLUMN. These three columns are
--       guaranteed absent on a v1 schema, so no guard is needed.
-- ─────────────────────────────────────────────────────────────────────────────

-- ALTER TABLE expenses ADD COLUMN merchant  VARCHAR(255) DEFAULT NULL;
-- ALTER TABLE expenses ADD COLUMN currency  VARCHAR(10)  NOT NULL DEFAULT 'INR';
-- ALTER TABLE expenses ADD COLUMN source    VARCHAR(50)  NOT NULL DEFAULT 'MANUAL';

-- ─────────────────────────────────────────────────────────────────────────────
-- Payment reference upgrade (run on any database created before this column)
--
-- The server refuses to start without these columns rather than failing on
-- every sync request, so if you are reading this because startup told you to,
-- run the two statements below and start it again.
-- ─────────────────────────────────────────────────────────────────────────────

-- ALTER TABLE expenses ADD COLUMN reference VARCHAR(64) DEFAULT NULL;
-- ALTER TABLE incomes  ADD COLUMN reference VARCHAR(64) DEFAULT NULL;
-- ALTER TABLE expenses ADD COLUMN date_is_assumed TINYINT(1) NOT NULL DEFAULT 0;
-- ALTER TABLE incomes  ADD COLUMN date_is_assumed TINYINT(1) NOT NULL DEFAULT 0;

-- ─────────────────────────────────────────────────────────────────────────────
-- Recurring-payment detection upgrade (run on any database created before it)
--
-- As above, the server refuses to start without these rather than failing every
-- recurring sync, so if startup told you to run something, it is below. The
-- dismissed_recurring_candidates table is created by the CREATE above on a
-- fresh install; on an existing database, run that statement too.
-- ─────────────────────────────────────────────────────────────────────────────

-- ALTER TABLE recurring ADD COLUMN currency VARCHAR(10) NOT NULL DEFAULT 'INR';
-- ALTER TABLE recurring ADD COLUMN nature VARCHAR(30) NOT NULL DEFAULT 'Spending';
-- ALTER TABLE recurring ADD COLUMN category VARCHAR(100) NOT NULL DEFAULT 'Other';
-- ALTER TABLE recurring ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'MANUAL';
-- ALTER TABLE recurring ADD COLUMN occurrence_count INT NOT NULL DEFAULT 0;
-- ALTER TABLE recurring ADD COLUMN confidence DOUBLE NOT NULL DEFAULT 1;
-- ALTER TABLE recurring ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
-- ALTER TABLE recurring ADD COLUMN last_occurrence_date BIGINT DEFAULT NULL;
-- ALTER TABLE recurring ADD COLUMN next_due_date BIGINT DEFAULT NULL;
-- ALTER TABLE recurring ADD COLUMN due_day_of_month INT DEFAULT NULL;
