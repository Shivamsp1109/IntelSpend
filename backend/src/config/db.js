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
  { table: 'incomes', column: 'date_is_assumed', definition: 'TINYINT(1) NOT NULL DEFAULT 0' }
];

async function assertSchemaIsCurrent() {
  const missing = [];

  for (const required of REQUIRED_COLUMNS) {
    const [rows] = await pool.execute(
      `SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
        LIMIT 1`,
      [required.table, required.column]
    );
    if (rows.length === 0) missing.push(required);
  }

  if (missing.length > 0) {
    // The definition travels with each column so the message is a statement
    // that can be pasted and run, not a template to be adapted — the types
    // differ, and guessing one wrong is a second failed startup.
    const statements = missing
      .map(({ table, column, definition }) =>
        `  ALTER TABLE ${table} ADD COLUMN ${column} ${definition};`)
      .join('\n');

    throw new Error(
      'The database is missing columns this version needs:\n' +
      `${statements}\n` +
      'Run the statements above (see the upgrade section of schema.sql), then start again.'
    );
  }
}

module.exports = {
  pool,
  assertDatabaseConnection,
  assertSchemaIsCurrent
};
