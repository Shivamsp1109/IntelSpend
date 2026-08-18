const mysql = require('mysql2/promise');

/**
 * TLS to the database.
 *
 * Off by default because the common local setup is MySQL on the same host,
 * where the connection never leaves the machine. The moment the database is on
 * a different host — a managed instance, another container, anything across a
 * network — set MYSQL_SSL=true, or every transaction and every user's email
 * crosses that network in plaintext.
 *
 * MYSQL_SSL_CA points at the provider's CA bundle when they issue one. Without
 * it the driver still encrypts but cannot verify who it is talking to, which
 * stops passive sniffing but not an active man in the middle.
 */
function tlsOptions() {
  if (String(process.env.MYSQL_SSL).toLowerCase() !== 'true') return undefined;

  const ca = process.env.MYSQL_SSL_CA;
  return ca
    ? { ca: require('fs').readFileSync(ca), rejectUnauthorized: true }
    : { rejectUnauthorized: true };
}

const pool = mysql.createPool({
  host: process.env.MYSQL_HOST,
  port: Number(process.env.MYSQL_PORT || 3306),
  user: process.env.MYSQL_USER,
  password: process.env.MYSQL_PASSWORD,
  database: process.env.MYSQL_DATABASE,
  ssl: tlsOptions(),
  waitForConnections: true,
  connectionLimit: 10,
  queueLimit: 0
});

async function assertDatabaseConnection() {
  const connection = await pool.getConnection();
  try {
    await connection.ping();
  } finally {
    connection.release();
  }
}

/**
 * Columns added after the first release, checked at startup.
 *
 * Without this the server starts happily and then fails every single sync with
 * "Unknown column 'reference' in 'field list'" — a message the phone reports as
 * a generic sync failure, on every request, with nothing pointing at the cause.
 * Refusing to start turns that into one clear line at deploy time, next to the
 * statement that fixes it.
 */
const REQUIRED_COLUMNS = [
  // Goal terms. `amount_basis` decides whether a target gets inflated at all —
  // a target already stated in future money must not be inflated a second time.
  { table: 'goals', column: 'currency', definition: "VARCHAR(10) NOT NULL DEFAULT 'INR'" },
  {
    table: 'goals',
    column: 'amount_basis',
    definition: "ENUM('TODAYS_MONEY','NOMINAL_FUTURE','MANUALLY_FIXED') NOT NULL DEFAULT 'TODAYS_MONEY'"
  },
  {
    table: 'goals',
    column: 'priority',
    definition: "ENUM('ESSENTIAL','IMPORTANT','NICE_TO_HAVE') NOT NULL DEFAULT 'IMPORTANT'"
  },
  {
    table: 'goals',
    column: 'flexibility',
    definition: "ENUM('DATE_FLEXIBLE','AMOUNT_FLEXIBLE','BOTH_FLEXIBLE','FIXED') NOT NULL DEFAULT 'BOTH_FLEXIBLE'"
  },
  {
    table: 'goals',
    column: 'status',
    definition: "ENUM('ACTIVE','PAUSED','ABANDONED','ACHIEVED') NOT NULL DEFAULT 'ACTIVE'"
  },
  { table: 'goals', column: 'funding_source', definition: 'VARCHAR(160) DEFAULT NULL' },

  // Which feature spent the tokens, so chat and extraction have separate caps.
  // One budget for both fails in two different ways: an import exhausting the
  // ability to ask a question, or a conversation blocking the next import.
  { table: 'llm_usage', column: 'feature', definition: "VARCHAR(32) NOT NULL DEFAULT 'EXTRACT'" },

  { table: 'expenses', column: 'reference', definition: 'VARCHAR(64) DEFAULT NULL' },
  { table: 'incomes', column: 'reference', definition: 'VARCHAR(64) DEFAULT NULL' },
  { table: 'expenses', column: 'date_is_assumed', definition: 'TINYINT(1) NOT NULL DEFAULT 0' },
  { table: 'incomes', column: 'date_is_assumed', definition: 'TINYINT(1) NOT NULL DEFAULT 0' },
  { table: 'recurring', column: 'currency', definition: "VARCHAR(10) NOT NULL DEFAULT 'INR'" },
  { table: 'recurring', column: 'nature', definition: "VARCHAR(30) NOT NULL DEFAULT 'Spending'" },
  { table: 'recurring', column: 'category', definition: "VARCHAR(100) NOT NULL DEFAULT 'Other'" },
  { table: 'recurring', column: 'source', definition: "VARCHAR(20) NOT NULL DEFAULT 'MANUAL'" },
  { table: 'recurring', column: 'occurrence_count', definition: 'INT NOT NULL DEFAULT 0' },
  { table: 'recurring', column: 'confidence', definition: 'DOUBLE NOT NULL DEFAULT 1' },
  { table: 'recurring', column: 'status', definition: "VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'" },
  { table: 'recurring', column: 'last_occurrence_date', definition: 'BIGINT DEFAULT NULL' },
  { table: 'recurring', column: 'next_due_date', definition: 'BIGINT DEFAULT NULL' },
  { table: 'recurring', column: 'due_day_of_month', definition: 'INT DEFAULT NULL' },
  { table: 'recurring', column: 'pending_amount', definition: 'DOUBLE DEFAULT NULL' },
  { table: 'recurring', column: 'declined_amount', definition: 'DOUBLE DEFAULT NULL' }
];

/**
 * Tables added after the first release.
 *
 * Same reasoning as the columns above, but a missing table needs the whole
 * CREATE rather than an ALTER, so the statement is carried here in full for the
 * same reason: the message should be something to paste and run, not a template
 * to reconstruct.
 */
const REQUIRED_TABLES = [
  {
    table: 'dismissed_recurring_candidates',
    definition: `CREATE TABLE IF NOT EXISTS dismissed_recurring_candidates (
    id                        INT AUTO_INCREMENT PRIMARY KEY,
    uid                       VARCHAR(128) NOT NULL,
    signature                 VARCHAR(512) NOT NULL,
    merchant                  VARCHAR(255) NOT NULL,
    currency                  VARCHAR(10) NOT NULL DEFAULT 'INR',
    nature                    VARCHAR(30) NOT NULL DEFAULT 'Spending',
    category                  VARCHAR(100) NOT NULL DEFAULT 'Other',
    cadence                   VARCHAR(20) NOT NULL,
    last_seen_amount          DOUBLE NOT NULL DEFAULT 0,
    last_seen_occurrence_date BIGINT NOT NULL DEFAULT 0,
    dismissed_at              BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY unique_user_dismissal (uid, signature(191)),
    CONSTRAINT fk_dismissal_user
        FOREIGN KEY (uid) REFERENCES users(uid)
        ON DELETE CASCADE
  );`
  },
  {
    table: 'decision_traces',
    definition: `CREATE TABLE IF NOT EXISTS decision_traces (
    trace_id                CHAR(36) PRIMARY KEY,
    uid                     VARCHAR(128) NOT NULL,
    user_question           TEXT DEFAULT NULL,
    intent                  VARCHAR(64) NOT NULL,
    snapshot_id             CHAR(36) DEFAULT NULL,
    engine_version          VARCHAR(20) NOT NULL,
    policy_version          VARCHAR(20) NOT NULL,
    constraint_versions_json JSON NOT NULL,
    payload_schema_version  INT NOT NULL,
    consent_state           JSON DEFAULT NULL,
    model_provider          VARCHAR(64) DEFAULT NULL,
    model_name              VARCHAR(128) DEFAULT NULL,
    selected_candidate_id   VARCHAR(64) DEFAULT NULL,
    rejected_candidate_ids_json JSON NOT NULL,
    payload                 JSON DEFAULT NULL,
    created_at              BIGINT NOT NULL,
    redacted_at             BIGINT DEFAULT NULL,
    INDEX idx_trace_user_time (uid, created_at DESC),
    INDEX idx_trace_snapshot (snapshot_id),
    INDEX idx_trace_retention (created_at),
    CONSTRAINT fk_trace_user
        FOREIGN KEY (uid) REFERENCES users(uid) ON DELETE CASCADE,
    CONSTRAINT fk_trace_snapshot
        FOREIGN KEY (snapshot_id) REFERENCES financial_snapshots(snapshot_id) ON DELETE SET NULL
  );`
  },
  {
    table: 'assumption_sets',
    definition: `CREATE TABLE IF NOT EXISTS assumption_sets (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    uid                 VARCHAR(128) DEFAULT NULL,
    scenario_type       ENUM('CONSERVATIVE','BASE','OPTIMISTIC') NOT NULL,
    asset_class         ENUM('CASH','DEBT','EQUITY','GOLD','REAL_ESTATE','BLENDED','NONE') NOT NULL DEFAULT 'NONE',
    time_horizon_band   ENUM('SHORT','MEDIUM','LONG','ANY') NOT NULL DEFAULT 'ANY',
    jurisdiction        VARCHAR(8) NOT NULL DEFAULT 'IN',
    currency            VARCHAR(10) NOT NULL DEFAULT 'INR',
    inflation_rate      DECIMAL(7, 4) DEFAULT NULL,
    expected_return     DECIMAL(7, 4) DEFAULT NULL,
    income_growth_rate  DECIMAL(7, 4) DEFAULT NULL,
    expense_growth_rate DECIMAL(7, 4) DEFAULT NULL,
    source              VARCHAR(500) NOT NULL,
    methodology         VARCHAR(500) DEFAULT NULL,
    effective_from      BIGINT NOT NULL,
    effective_to        BIGINT DEFAULT NULL,
    version             VARCHAR(20) NOT NULL,
    approval_status     ENUM('DRAFT','APPROVED','SUPERSEDED') NOT NULL DEFAULT 'DRAFT',
    updated_at          BIGINT NOT NULL,
    INDEX idx_assumption_lookup (scenario_type, asset_class, time_horizon_band, jurisdiction, approval_status),
    INDEX idx_assumption_user (uid),
    CONSTRAINT fk_assumption_user
        FOREIGN KEY (uid) REFERENCES users(uid) ON DELETE CASCADE
  );

  -- NOTE: the system assumption sets must also be seeded. Run migrations
  -- (npm run migrate) rather than pasting this, or projections will find no
  -- approved assumptions and refuse to produce a figure.`
  },
  {
    table: 'scenario_runs',
    definition: `CREATE TABLE IF NOT EXISTS scenario_runs (
    run_id            CHAR(36) PRIMARY KEY,
    uid               VARCHAR(128) NOT NULL,
    snapshot_id       CHAR(36) DEFAULT NULL,
    assumption_set_id BIGINT DEFAULT NULL,
    scenario_type     ENUM('CONSERVATIVE','BASE','OPTIMISTIC') NOT NULL,
    subject_type      ENUM('GOAL','DEBT_PAYOFF','PORTFOLIO','CASH_FLOW') NOT NULL,
    subject_local_id  INT DEFAULT NULL,
    inputs_hash       CHAR(64) NOT NULL,
    outputs_json      JSON NOT NULL,
    engine_version    VARCHAR(20) NOT NULL,
    run_at            BIGINT NOT NULL,
    INDEX idx_scenario_run_user (uid, run_at DESC),
    INDEX idx_scenario_run_snapshot (snapshot_id),
    CONSTRAINT fk_scenario_run_user
        FOREIGN KEY (uid) REFERENCES users(uid) ON DELETE CASCADE,
    CONSTRAINT fk_scenario_run_snapshot
        FOREIGN KEY (snapshot_id) REFERENCES financial_snapshots(snapshot_id) ON DELETE SET NULL,
    CONSTRAINT fk_scenario_run_assumptions
        FOREIGN KEY (assumption_set_id) REFERENCES assumption_sets(id) ON DELETE SET NULL
  );`
  },
  {
    table: 'insurance_policies',
    definition: `CREATE TABLE IF NOT EXISTS insurance_policies (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    uid               VARCHAR(128) NOT NULL,
    local_id          INT NOT NULL,
    policy_type       ENUM('TERM_LIFE','WHOLE_LIFE','ENDOWMENT','ULIP','HEALTH','CRITICAL_ILLNESS','PERSONAL_ACCIDENT','MOTOR','HOME','TRAVEL','OTHER') NOT NULL DEFAULT 'OTHER',
    provider          VARCHAR(160) DEFAULT NULL,
    label             VARCHAR(255) NOT NULL,
    sum_assured       DECIMAL(18, 2) NOT NULL,
    currency          VARCHAR(10) NOT NULL DEFAULT 'INR',
    premium_amount    DECIMAL(18, 2) DEFAULT NULL,
    premium_cadence   ENUM('MONTHLY','QUARTERLY','HALF_YEARLY','YEARLY','SINGLE') NOT NULL DEFAULT 'YEARLY',
    policy_end_date   BIGINT DEFAULT NULL,
    nominee_set       TINYINT(1) DEFAULT NULL,
    updated_at        BIGINT NOT NULL,
    UNIQUE KEY unique_policy_per_user (uid, local_id),
    INDEX idx_insurance_type (uid, policy_type),
    CONSTRAINT fk_insurance_user
        FOREIGN KEY (uid) REFERENCES users(uid) ON DELETE CASCADE
  );`
  },
  {
    table: 'risk_assessments',
    definition: `CREATE TABLE IF NOT EXISTS risk_assessments (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    uid                   VARCHAR(128) NOT NULL,
    risk_tolerance        ENUM('LOW','MODERATE','HIGH') DEFAULT NULL,
    risk_capacity         ENUM('LOW','MODERATE','HIGH') DEFAULT NULL,
    risk_need             ENUM('LOW','MODERATE','HIGH') DEFAULT NULL,
    questionnaire_version VARCHAR(20) NOT NULL,
    answers_json          JSON DEFAULT NULL,
    assessment_date       BIGINT NOT NULL,
    limitations           JSON DEFAULT NULL,
    user_confirmed        TINYINT(1) NOT NULL DEFAULT 0,
    updated_at            BIGINT NOT NULL,
    UNIQUE KEY unique_risk_per_user (uid),
    CONSTRAINT fk_risk_user
        FOREIGN KEY (uid) REFERENCES users(uid) ON DELETE CASCADE
  );`
  },
  {
    table: 'loan_details',
    definition: `CREATE TABLE IF NOT EXISTS loan_details (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    uid                     VARCHAR(128) NOT NULL,
    recurring_local_id      INT NOT NULL,
    principal_outstanding   DECIMAL(18, 2) NOT NULL,
    outstanding_as_of       BIGINT NOT NULL,
    currency                VARCHAR(10) NOT NULL DEFAULT 'INR',
    interest_rate           DECIMAL(7, 4) DEFAULT NULL,
    rate_type               ENUM('FIXED', 'VARIABLE', 'UNKNOWN') NOT NULL DEFAULT 'UNKNOWN',
    rate_reset_date         BIGINT DEFAULT NULL,
    interest_compounding    ENUM('MONTHLY', 'ANNUAL', 'UNKNOWN') NOT NULL DEFAULT 'UNKNOWN',
    scheduled_payment       DECIMAL(18, 2) DEFAULT NULL,
    payment_frequency       ENUM('MONTHLY', 'QUARTERLY', 'YEARLY', 'WEEKLY', 'BIWEEKLY') NOT NULL DEFAULT 'MONTHLY',
    remaining_installments  INT DEFAULT NULL,
    next_payment_date       BIGINT DEFAULT NULL,
    prepayment_charge_type  ENUM('NONE', 'FLAT', 'PERCENT_OF_PRINCIPAL', 'UNKNOWN') NOT NULL DEFAULT 'UNKNOWN',
    prepayment_charge_value DECIMAL(18, 4) DEFAULT NULL,
    fees_or_penalties       DECIMAL(18, 2) DEFAULT NULL,
    updated_at              BIGINT NOT NULL,
    UNIQUE KEY unique_loan_per_commitment (uid, recurring_local_id),
    CONSTRAINT fk_loan_details_user
        FOREIGN KEY (uid) REFERENCES users(uid) ON DELETE CASCADE,
    CONSTRAINT fk_loan_details_recurring
        FOREIGN KEY (uid, recurring_local_id) REFERENCES recurring(uid, local_id) ON DELETE CASCADE
  );`
  },
  {
    table: 'assets',
    definition: `CREATE TABLE IF NOT EXISTS assets (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    uid                 VARCHAR(128) NOT NULL,
    local_id            INT NOT NULL,
    asset_type          ENUM('CASH','BANK_ACCOUNT','FIXED_DEPOSIT','RECURRING_DEPOSIT','MUTUAL_FUND','STOCK','BOND','ETF','PROVIDENT_FUND','PENSION','NPS','GOLD','REAL_ESTATE','VEHICLE','INSURANCE_CASH_VALUE','CRYPTO','LOAN_GIVEN','OTHER') NOT NULL DEFAULT 'OTHER',
    label               VARCHAR(255) NOT NULL,
    current_value       DECIMAL(18, 2) NOT NULL,
    currency            VARCHAR(10) NOT NULL DEFAULT 'INR',
    valuation_date      BIGINT NOT NULL,
    liquidity_class     ENUM('LIQUID_CASH','LIQUID_INVESTMENT','ILLIQUID_INVESTMENT','PHYSICAL','RETIREMENT_LOCKED') NOT NULL DEFAULT 'ILLIQUID_INVESTMENT',
    lock_in_until       BIGINT DEFAULT NULL,
    ownership           ENUM('SELF', 'JOINT', 'FAMILY') NOT NULL DEFAULT 'SELF',
    verification_source ENUM('MANUAL', 'IMPORTED', 'CONFIRMED') NOT NULL DEFAULT 'MANUAL',
    account_type        VARCHAR(120) DEFAULT NULL,
    updated_at          BIGINT NOT NULL,
    UNIQUE KEY unique_asset_per_user (uid, local_id),
    INDEX idx_assets_liquidity (uid, liquidity_class),
    CONSTRAINT fk_assets_user
        FOREIGN KEY (uid) REFERENCES users(uid) ON DELETE CASCADE
  );`
  },
  {
    table: 'financial_snapshots',
    definition: `CREATE TABLE IF NOT EXISTS financial_snapshots (
    snapshot_id            CHAR(36) PRIMARY KEY,
    uid                    VARCHAR(128) NOT NULL,
    observed_state_json    JSON NOT NULL,
    data_quality_json      JSON NOT NULL,
    source_watermarks_json JSON NOT NULL,
    snapshot_hash          CHAR(64) NOT NULL,
    engine_version         VARCHAR(20) NOT NULL,
    payload_schema_version INT NOT NULL,
    computed_at            BIGINT NOT NULL,
    period_start           BIGINT NOT NULL,
    period_end             BIGINT NOT NULL,
    currency               VARCHAR(10) NOT NULL DEFAULT 'INR',
    timezone               VARCHAR(64) NOT NULL DEFAULT 'Asia/Kolkata',
    INDEX idx_snapshot_user_time (uid, computed_at DESC),
    CONSTRAINT fk_snapshot_user
        FOREIGN KEY (uid) REFERENCES users(uid)
        ON DELETE CASCADE
  );`
  },
  {
    table: 'category_budgets',
    definition: `CREATE TABLE IF NOT EXISTS category_budgets (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    uid           VARCHAR(128) NOT NULL,
    local_id      INT NOT NULL,
    category      VARCHAR(100) NOT NULL,
    monthly_limit DOUBLE NOT NULL,
    currency      VARCHAR(10) NOT NULL DEFAULT 'INR',
    updated_at    BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY unique_user_category_budget (uid, local_id),
    UNIQUE KEY unique_user_category (uid, category, currency),
    CONSTRAINT fk_category_budgets_user
        FOREIGN KEY (uid) REFERENCES users(uid)
        ON DELETE CASCADE
  );`
  }
];

/**
 * Whether the migration runner has been run to the version this code expects.
 *
 * The checks below catch a specific missing table or column. This catches the
 * more useful thing: that `npm run migrate` was not run at all after a deploy.
 * Kept separate from the runner itself so the server never applies schema
 * changes as a side effect of starting — a process that migrates on boot will
 * eventually do it from three instances at once.
 */
async function assertMigrationsAreApplied() {
  // Required lazily: migrate.js requires this module, and at module scope that
  // is a cycle that leaves one of the two half-initialised.
  const { latestVersionOnDisk } = require('../migrate');
  const expected = latestVersionOnDisk();
  if (expected === 0) return;

  const [ledger] = await pool.query(
    `SELECT 1 FROM information_schema.tables
      WHERE table_schema = DATABASE() AND table_name = 'schema_migrations'
      LIMIT 1`
  );

  if (ledger.length === 0) {
    throw new Error(
      'This database has never been migrated.\n' +
      '  Run: npm run migrate\n' +
      '(On a brand new database, load schema.sql first.)'
    );
  }

  const [rows] = await pool.query(
    'SELECT COALESCE(MAX(version), 0) AS version FROM schema_migrations'
  );
  const applied = Number(rows[0].version);

  if (applied < expected) {
    throw new Error(
      `The database is at migration ${applied}, this version needs ${expected}.\n` +
      '  Run: npm run migrate'
    );
  }
}

async function assertSchemaIsCurrent() {
  await assertMigrationsAreApplied();

  const statements = [];

  // Tables first: a missing table makes every one of its columns report missing
  // too, and a wall of ALTERs against a table that does not exist is worse than
  // useless as an instruction.
  const missingTables = new Set();

  for (const required of REQUIRED_TABLES) {
    const [rows] = await pool.execute(
      `SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = ?
        LIMIT 1`,
      [required.table]
    );
    if (rows.length === 0) {
      missingTables.add(required.table);
      statements.push(required.definition);
    }
  }

  for (const required of REQUIRED_COLUMNS) {
    if (missingTables.has(required.table)) continue;

    const [rows] = await pool.execute(
      `SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
        LIMIT 1`,
      [required.table, required.column]
    );
    if (rows.length === 0) {
      // The definition travels with each column so the message is a statement
      // that can be pasted and run, not a template to be adapted — the types
      // differ, and guessing one wrong is a second failed startup.
      statements.push(
        `ALTER TABLE ${required.table} ADD COLUMN ${required.column} ${required.definition};`
      );
    }
  }

  if (statements.length > 0) {
    throw new Error(
      'The database is missing things this version needs:\n' +
      `${statements.map((statement) => `  ${statement}`).join('\n')}\n` +
      'Run the statements above (see the upgrade section of schema.sql), then start again.'
    );
  }
}

module.exports = {
  pool,
  assertDatabaseConnection,
  assertSchemaIsCurrent,
  assertMigrationsAreApplied
};
