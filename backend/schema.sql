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
    INDEX idx_goals_uid (uid),
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
    updated_at BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY unique_user_recurring (uid, local_id),
    INDEX idx_recurring_uid (uid),
    CONSTRAINT fk_recurring_user
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

-- ─────────────────────────────────────────────────────────────────────────────
-- v1 → v2 upgrade path (ONLY run this block on an existing v1 database)
-- NOTE: ALTER TABLE ... ADD COLUMN IF NOT EXISTS is MariaDB-only.
--       Standard MySQL requires plain ADD COLUMN. These three columns are
--       guaranteed absent on a v1 schema, so no guard is needed.
-- ─────────────────────────────────────────────────────────────────────────────

-- ALTER TABLE expenses ADD COLUMN merchant  VARCHAR(255) DEFAULT NULL;
-- ALTER TABLE expenses ADD COLUMN currency  VARCHAR(10)  NOT NULL DEFAULT 'INR';
-- ALTER TABLE expenses ADD COLUMN source    VARCHAR(50)  NOT NULL DEFAULT 'MANUAL';
