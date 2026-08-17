/**
 * The migration runner's file handling.
 *
 * Run with: npm test
 *
 * Covers the parts that work without a database, which is where the damage
 * would be: applying files in the wrong order, or accepting a file whose
 * position in the sequence is undefined. The apply-and-record path needs a live
 * MySQL and is verified by hand against a scratch database — see the Stage 0
 * checkpoint in the plan.
 */
const assert = require('assert');
const path = require('path');
const fs = require('fs');
const { loadMigrations, latestVersionOnDisk, statementsIn } = require('../src/migrate');

function ok(name, fn) {
  fn();
  console.log(`  ok  ${name}`);
}

(async () => {
  console.log('migration runner');

  ok('migrations load in numeric order, not alphabetical', () => {
    // The trap this guards: sorting '010' before '002' as strings would run a
    // later migration against a schema that has not been prepared for it.
    const migrations = loadMigrations();
    const versions = migrations.map((migration) => migration.version);

    assert.deepStrictEqual(versions, [...versions].sort((a, b) => a - b));
    assert.ok(versions.length > 0, 'expected at least the baseline migration');
    assert.strictEqual(versions[0], 1);
  });

  ok('every migration on disk is named so its order is unambiguous', () => {
    const dir = path.join(__dirname, '..', 'migrations');
    const files = fs.readdirSync(dir).filter((file) => file.endsWith('.sql'));

    files.forEach((file) => {
      assert.match(file, /^\d{3}_[a-z0-9_]+\.sql$/, `${file} is misnamed`);
    });
  });

  ok('version numbers are unique', () => {
    const versions = loadMigrations().map((migration) => migration.version);
    assert.strictEqual(new Set(versions).size, versions.length);
  });

  ok('the latest version on disk is the highest number, not the file count', () => {
    const migrations = loadMigrations();
    assert.strictEqual(
      latestVersionOnDisk(),
      Math.max(...migrations.map((migration) => migration.version))
    );
  });

  ok('statements split on semicolons and drop comment-only lines', () => {
    const sql = `
      -- a leading comment
      CREATE TABLE a (id INT);
      CREATE TABLE b (id INT);
    `;

    const statements = statementsIn(sql);

    assert.strictEqual(statements.length, 2);
    assert.ok(statements[0].startsWith('CREATE TABLE a'));
    assert.ok(statements[1].startsWith('CREATE TABLE b'));
  });

  ok('a file of only comments yields nothing to run', () => {
    assert.deepStrictEqual(statementsIn('-- nothing here\n-- still nothing\n'), []);
  });

  console.log('\nall migration runner tests passed');
})();
