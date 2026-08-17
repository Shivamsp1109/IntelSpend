/**
 * Applies numbered schema migrations, in order, exactly once each.
 *
 * Run with: npm run migrate
 *
 * Replaces hand-pasting DDL out of a startup error message. That worked while
 * there were two tables to add; across the financial engine's ten-plus it stops
 * being reliable — a statement gets missed, or run twice, or run out of order
 * against a database nobody is quite sure the state of, and the failure surfaces
 * later as a sync error that says nothing about the cause.
 *
 * Deliberately plain: files named NNN_description.sql, applied in numeric order,
 * recorded in `schema_migrations`. No down-migrations. Reversing a schema change
 * on live financial data is not something to trigger from a script — it needs a
 * person looking at the data first, and a forward migration that undoes it.
 */
const fs = require('fs');
const path = require('path');
const { pool } = require('./config/db');

const MIGRATIONS_DIR = path.join(__dirname, '..', 'migrations');

const CREATE_LEDGER = `CREATE TABLE IF NOT EXISTS schema_migrations (
    version     INT PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    applied_at  BIGINT NOT NULL,
    checksum    CHAR(64) NOT NULL
  );`;

/** Reads the migration files off disk, ordered by their number. */
function loadMigrations() {
  if (!fs.existsSync(MIGRATIONS_DIR)) return [];

  return fs.readdirSync(MIGRATIONS_DIR)
    .filter((file) => file.endsWith('.sql'))
    .map((file) => {
      const match = /^(\d+)_(.+)\.sql$/.exec(file);
      if (!match) {
        throw new Error(
          `Migration '${file}' is not named NNN_description.sql, so its order is undefined.`
        );
      }
      return {
        version: Number(match[1]),
        name: match[2],
        file,
        sql: fs.readFileSync(path.join(MIGRATIONS_DIR, file), 'utf8')
      };
    })
    .sort((a, b) => a.version - b.version);
}

function checksum(sql) {
  return require('crypto').createHash('sha256').update(sql).digest('hex');
}

/**
 * Splits a file into statements.
 *
 * Naive on purpose — semicolons outside of string literals. Migration files are
 * DDL written by hand in this repo, not arbitrary input, and DDL that needs a
 * real parser to split is DDL that should be in its own file.
 */
function statementsIn(sql) {
  return sql
    .split(/;\s*$/m)
    .map((statement) => statement.replace(/^\s*--.*$/gm, '').trim())
    .filter((statement) => statement.length > 0);
}

async function appliedVersions(connection) {
  const [rows] = await connection.query(
    'SELECT version, name, checksum FROM schema_migrations ORDER BY version ASC'
  );
  return rows;
}

async function migrate({ quiet = false } = {}) {
  const log = quiet ? () => {} : console.log;
  const migrations = loadMigrations();
  const connection = await pool.getConnection();

  try {
    await connection.query(CREATE_LEDGER);

    const applied = await appliedVersions(connection);
    const appliedBy = new Map(applied.map((row) => [row.version, row]));

    // A migration whose contents changed after it ran means the file on disk no
    // longer describes the live schema. Silently skipping it would leave the two
    // permanently and invisibly out of step.
    for (const migration of migrations) {
      const record = appliedBy.get(migration.version);
      if (record && record.checksum !== checksum(migration.sql)) {
        throw new Error(
          `Migration ${migration.file} has changed since it was applied. ` +
          'Add a new migration instead of editing one that has already run.'
        );
      }
    }

    const pending = migrations.filter((migration) => !appliedBy.has(migration.version));

    if (pending.length === 0) {
      log(`Schema is up to date (${applied.length} migration(s) applied).`);
      return { applied: [], alreadyApplied: applied.length };
    }

    const done = [];
    for (const migration of pending) {
      log(`Applying ${migration.file}...`);

      // MySQL commits DDL implicitly, so a multi-statement migration cannot be
      // rolled back as a unit. Each one is applied statement by statement and
      // the ledger row is written only after all of them land, so a failure
      // halfway leaves the migration unrecorded and visibly incomplete rather
      // than marked done.
      for (const statement of statementsIn(migration.sql)) {
        await connection.query(statement);
      }

      await connection.query(
        'INSERT INTO schema_migrations (version, name, applied_at, checksum) VALUES (?, ?, ?, ?)',
        [migration.version, migration.name, Date.now(), checksum(migration.sql)]
      );
      done.push(migration.file);
      log(`  done ${migration.file}`);
    }

    log(`\nApplied ${done.length} migration(s).`);
    return { applied: done, alreadyApplied: applied.length };
  } finally {
    connection.release();
  }
}

/** The highest migration on disk — what a correctly-migrated database sits at. */
function latestVersionOnDisk() {
  const migrations = loadMigrations();
  return migrations.length === 0 ? 0 : migrations[migrations.length - 1].version;
}

async function appliedVersion() {
  const [rows] = await pool.query(
    'SELECT COALESCE(MAX(version), 0) AS version FROM schema_migrations'
  );
  return Number(rows[0].version);
}

module.exports = { migrate, loadMigrations, latestVersionOnDisk, appliedVersion, statementsIn };

if (require.main === module) {
  migrate()
    .then(() => process.exit(0))
    .catch((error) => {
      console.error(`\nMigration failed: ${error.message}`);
      process.exit(1);
    });
}
