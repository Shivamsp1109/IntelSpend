/**
 * Cursor pagination for the restore endpoints.
 *
 * Run with: npm test
 *
 * The cursor is what makes a restore resumable, and `nextAfter` is derived
 * rather than counted — so the edge where a page happens to land exactly on the
 * limit is worth pinning, because getting it wrong either truncates someone's
 * history or loops forever.
 */
const assert = require('assert');
const { pageParams, page, DEFAULT_LIMIT, MAX_LIMIT } = require('../src/services/pagination');

function ok(name, fn) {
  fn();
  console.log(`  ok  ${name}`);
}

function rows(count, startId = 1) {
  return Array.from({ length: count }, (_, i) => ({ localId: startId + i }));
}

(async () => {
  console.log('restore pagination');

  ok('defaults apply when nothing is asked for', () => {
    assert.deepStrictEqual(pageParams({}), { after: 0, limit: DEFAULT_LIMIT });
    assert.deepStrictEqual(pageParams(undefined), { after: 0, limit: DEFAULT_LIMIT });
  });

  ok('a cursor and limit are read from the query', () => {
    assert.deepStrictEqual(pageParams({ after: '120', limit: '50' }), { after: 120, limit: 50 });
  });

  // A restore failing on a malformed cursor helps nobody; the defaults are safe.
  ok('nonsense falls back instead of erroring', () => {
    assert.deepStrictEqual(pageParams({ after: 'abc', limit: 'xyz' }), { after: 0, limit: DEFAULT_LIMIT });
    assert.deepStrictEqual(pageParams({ after: '-5', limit: '0' }), { after: 0, limit: DEFAULT_LIMIT });
    assert.deepStrictEqual(pageParams({ after: '1.9', limit: '10.7' }), { after: 1, limit: 10 });
  });

  // The limit is interpolated into SQL rather than bound, because mysql2 sends
  // prepared-statement placeholders as strings and MySQL rejects that in LIMIT.
  ok('the limit is clamped and always a safe integer', () => {
    assert.strictEqual(pageParams({ limit: '99999' }).limit, MAX_LIMIT);
    assert.strictEqual(pageParams({ limit: String(MAX_LIMIT) }).limit, MAX_LIMIT);
    assert.ok(Number.isSafeInteger(pageParams({ limit: '1e21' }).limit));
    assert.ok(Number.isSafeInteger(pageParams({ limit: 'Infinity' }).limit));
  });

  ok('a partial page ends the sequence', () => {
    const result = page(rows(30), 200);
    assert.strictEqual(result.nextAfter, null, 'a short page means there is no more');
    assert.strictEqual(result.items.length, 30);
  });

  ok('an empty page ends the sequence', () => {
    assert.strictEqual(page([], 200).nextAfter, null);
  });

  // A full page cannot be distinguished from "exactly the last page", so it
  // reports a cursor and the client makes one more request that comes back
  // empty. Truncating here instead would silently drop the tail of a history
  // whose length happens to be a multiple of the page size.
  ok('a full page reports a cursor for the next one', () => {
    const result = page(rows(200, 501), 200);
    assert.strictEqual(result.nextAfter, 700, 'the cursor is the last id on the page');
  });

  ok('the cursor follows the last row, not the first', () => {
    assert.strictEqual(page(rows(5, 10), 5).nextAfter, 14);
  });

  console.log('\nall pagination tests passed');
})().catch((error) => {
  console.error('\nFAILED:', error.message);
  process.exit(1);
});
