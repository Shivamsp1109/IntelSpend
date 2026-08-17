/**
 * Complete calendar months, in the user's own timezone.
 *
 * A cash-flow baseline has to be built from whole months or it lies. Today is
 * the 3rd; the month so far holds one salary credit and none of the rent, and
 * averaging it in reports someone as saving far more than they do. The rule is
 * therefore structural rather than a caller's responsibility: the month
 * containing "now" never enters a baseline, and the window is stated on every
 * result so a figure can be checked against the period it came from.
 *
 * Timezone matters more than it looks. A month boundary is a wall-clock fact —
 * 1 August began at midnight where the user was, not at midnight UTC — and
 * computing it in the server's locale moves the edge by hours, silently pushing
 * transactions into the wrong month. Everything here works from an explicit zone
 * and never from the host's default.
 *
 * Built on Intl rather than a date library: the platform already carries the
 * IANA database, and a dependency here would be one more thing to keep current
 * as zones change.
 */

/** One month is a period, not a pattern. Below this, nothing is called a trend. */
const TREND_MIN_MONTHS = 3;

/** How far back a baseline will look, when the caller does not say. */
const DEFAULT_BASELINE_MONTHS = 6;

/**
 * Wall-clock fields for an instant in a zone.
 *
 * `hourCycle: 'h23'` because the alternative reports midnight as hour 24 on some
 * runtimes, which lands the first instant of a month in the previous one.
 */
function zonedParts(millis, timeZone) {
  const formatter = new Intl.DateTimeFormat('en-US', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23'
  });

  const parts = {};
  for (const part of formatter.formatToParts(new Date(millis))) {
    if (part.type !== 'literal') parts[part.type] = Number(part.value);
  }
  return parts;
}

/** The zone's offset from UTC at a given instant, in millis. */
function offsetAt(millis, timeZone) {
  const parts = zonedParts(millis, timeZone);
  const asIfUtc = Date.UTC(
    parts.year, parts.month - 1, parts.day,
    parts.hour, parts.minute, parts.second
  );
  return asIfUtc - Math.floor(millis / 1000) * 1000;
}

/**
 * The instant at which a given local midnight occurred.
 *
 * Solved in two passes because the offset depends on the answer: a first guess
 * uses the offset at the equivalent UTC instant, then re-reads the offset at the
 * candidate and corrects if the two disagree, which is what happens when the
 * boundary falls near a daylight-saving change.
 */
function startOfDayInstant(year, month, day, timeZone) {
  const wallClock = Date.UTC(year, month - 1, day, 0, 0, 0);
  const firstGuess = wallClock - offsetAt(wallClock, timeZone);
  const corrected = offsetAt(firstGuess, timeZone);
  return wallClock - corrected;
}

const monthKey = (year, month) => `${year}-${String(month).padStart(2, '0')}`;

/** Which calendar month an instant falls in, where the user is. */
function monthContaining(millis, timeZone) {
  const parts = zonedParts(millis, timeZone);
  return { year: parts.year, month: parts.month, key: monthKey(parts.year, parts.month) };
}

function addMonths({ year, month }, delta) {
  const zeroBased = (year * 12 + (month - 1)) + delta;
  return { year: Math.floor(zeroBased / 12), month: (zeroBased % 12) + 1 };
}

/**
 * A whole calendar month as a half-open range, stored inclusive to its last
 * millisecond because the queries above it use SQL `BETWEEN`.
 */
function monthRange(year, month, timeZone) {
  const start = startOfDayInstant(year, month, 1, timeZone);
  const next = addMonths({ year, month }, 1);
  const end = startOfDayInstant(next.year, next.month, 1, timeZone) - 1;
  return { key: monthKey(year, month), year, month, start, end };
}

/**
 * The most recent complete months, newest last.
 *
 * The month containing `now` is excluded — that is the whole point. A caller
 * cannot opt out, because every place that has been allowed to decide this for
 * itself eventually gets it wrong in the same direction: a part-month that
 * flatters the figures.
 */
function completeMonths({ now, timeZone, count = DEFAULT_BASELINE_MONTHS }) {
  if (!Number.isInteger(count) || count < 1) {
    throw new Error(`completeMonths needs a positive month count, got ${count}.`);
  }

  const current = monthContaining(now, timeZone);
  const months = [];

  for (let back = count; back >= 1; back -= 1) {
    const { year, month } = addMonths(current, -back);
    months.push(monthRange(year, month, timeZone));
  }

  return months;
}

/** The partial month a baseline deliberately leaves out, for reporting it. */
function currentPartialMonth({ now, timeZone }) {
  const current = monthContaining(now, timeZone);
  const range = monthRange(current.year, current.month, timeZone);
  const elapsed = now - range.start;
  const whole = range.end - range.start;

  return {
    ...range,
    fractionElapsed: whole === 0 ? 0 : Math.min(1, elapsed / whole)
  };
}

/** The span a set of months covers, for stating the window on a result. */
function windowOf(months) {
  if (months.length === 0) return null;
  return {
    start: months[0].start,
    end: months[months.length - 1].end,
    monthCount: months.length,
    firstKey: months[0].key,
    lastKey: months[months.length - 1].key
  };
}

module.exports = {
  TREND_MIN_MONTHS,
  DEFAULT_BASELINE_MONTHS,
  zonedParts,
  offsetAt,
  startOfDayInstant,
  monthKey,
  monthContaining,
  addMonths,
  monthRange,
  completeMonths,
  currentPartialMonth,
  windowOf
};
