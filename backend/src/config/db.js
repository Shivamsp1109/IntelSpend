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
  { table: 'recurring', column: 'due_day_of_month', definition: 'INT DEFAULT NULL' }
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
  }
];

async function assertSchemaIsCurrent() {
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
  assertSchemaIsCurrent
};
