/**
 * Cursor pagination for the restore endpoints.
 *
 * Restoring a device reads a user's entire history, which for a long-standing
 * account is thousands of rows. Returning that in one response risks a timeout
 * on a slow connection and a large allocation on both ends, and a half-received
 * body is not resumable — the phone would start again from nothing.
 *
 * A cursor over `local_id` fixes both. Rows come back in id order, each page
 * names where the next one starts, and an interrupted restore resumes from the
 * last page it actually stored.
 *
 * OFFSET would have been simpler and wrong: it re-scans the skipped rows on
 * every page, and a row written between two requests shifts the window so an
 * unrelated row is skipped entirely.
 */

const DEFAULT_LIMIT = 200;
const MAX_LIMIT = 500;

/**
 * Reads `after` and `limit` from a query string, clamped to something sane.
 * Nonsense values fall back to the defaults rather than erroring: a restore
 * failing on a malformed cursor helps nobody.
 *
 * Both come back as integers, which matters for `limit`. mysql2's `execute`
 * runs a prepared statement and sends placeholders as strings, and MySQL
 * rejects a string in LIMIT — so that one is interpolated into the SQL by the
 * callers rather than bound. Forcing it through Math.trunc and a clamp here is
 * what makes that safe, and is why the parsing lives in one place instead of
 * being repeated at each route.
 */
function pageParams(query) {
  const after = Number(query?.after);
  const limit = Number(query?.limit);

  const parsed = {
    after: Number.isFinite(after) && after > 0 ? Math.trunc(after) : 0,
    limit: Number.isFinite(limit) && limit > 0
      ? Math.min(Math.trunc(limit), MAX_LIMIT)
      : DEFAULT_LIMIT
  };

  // Interpolated into SQL below, so this is a hard guarantee rather than a hope.
  if (!Number.isSafeInteger(parsed.limit) || parsed.limit < 1 || parsed.limit > MAX_LIMIT) {
    throw new Error(`Refusing to page with a limit of ${parsed.limit}.`);
  }
  return parsed;
}

/**
 * Wraps rows in the envelope the client expects.
 *
 * `nextAfter` is null on the last page. It is derived from whether the page
 * came back full rather than from a total count: counting the whole table on
 * every page costs more than the page itself.
 *
 * `cursorField` exists because not every table pages on a column called
 * `localId` — loan terms are keyed by the commitment they belong to. Reading a
 * field that is not there would return undefined and silently end the restore
 * one page in, so the name is stated and verified rather than assumed.
 */
function page(rows, limit, cursorField = 'localId') {
  const complete = rows.length === limit;
  if (!complete) return { items: rows, nextAfter: null };

  const cursor = rows[rows.length - 1][cursorField];
  if (cursor === undefined) {
    throw new Error(
      `Cannot page: rows have no '${cursorField}' to continue from. ` +
      'A restore would stop here without saying why.'
    );
  }

  return { items: rows, nextAfter: cursor };
}

module.exports = { pageParams, page, DEFAULT_LIMIT, MAX_LIMIT };
