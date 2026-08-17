/**
 * Calendar months in the user's own timezone.
 *
 * Run with: npm test
 *
 * A month boundary is a wall-clock fact — 1 August began at midnight where the
 * user was, not at midnight UTC — and getting it wrong moves the edge by hours,
 * pushing transactions into the wrong month with nothing to show for it. These
 * pin the boundaries against zones that actually differ: a half-hour offset, a
 * daylight-saving change, and the far side of the date line.
 */
const assert = require('assert');
const {
  completeMonths,
  currentPartialMonth,
  monthContaining,
  monthRange,
  startOfDayInstant,
  addMonths,
  windowOf
} = require('../src/engine/periods');

function ok(name, fn) {
  fn();
  console.log(`  ok  ${name}`);
}

const iso = (millis) => new Date(millis).toISOString();

(async () => {
  console.log('month boundaries');

  ok('a month begins at local midnight, not UTC midnight', () => {
    // India is UTC+5:30, so 1 August there began at 18:30 UTC on 31 July.
    // Computing this in the server's locale would move the edge by hours.
    assert.strictEqual(iso(monthRange(2026, 8, 'Asia/Kolkata').start), '2026-07-31T18:30:00.000Z');
  });

  ok('a month ends on the last millisecond of its final day', () => {
    const august = monthRange(2026, 8, 'Asia/Kolkata');
    const september = monthRange(2026, 9, 'Asia/Kolkata');

    assert.strictEqual(september.start - august.end, 1);
  });

  ok('daylight saving moves the boundary by an hour', () => {
    // New York is UTC-5 in March and UTC-4 in April, and a fixed offset would
    // put one of these an hour into the neighbouring month.
    assert.strictEqual(iso(monthRange(2026, 3, 'America/New_York').start), '2026-03-01T05:00:00.000Z');
    assert.strictEqual(iso(monthRange(2026, 4, 'America/New_York').start), '2026-04-01T04:00:00.000Z');
  });

  ok('a zone ahead of UTC starts its month the previous day', () => {
    assert.strictEqual(iso(monthRange(2026, 8, 'Pacific/Auckland').start), '2026-07-31T12:00:00.000Z');
  });

  ok('February knows how long it is in a leap year', () => {
    const february = monthRange(2028, 2, 'Asia/Kolkata');
    const days = Math.round((february.end + 1 - february.start) / 86_400_000);

    assert.strictEqual(days, 29);
  });

  console.log('\ncomplete months');

  ok('the month containing now is never included', () => {
    // The rule the whole baseline rests on. Mid-August, August is not over, and
    // no caller gets to decide otherwise.
    const now = Date.UTC(2026, 7, 17, 6, 0);
    const months = completeMonths({ now, timeZone: 'Asia/Kolkata', count: 3 });

    assert.deepStrictEqual(months.map((month) => month.key), ['2026-05', '2026-06', '2026-07']);
  });

  ok('the last day of a month is still an incomplete month', () => {
    // 31 August, late evening local time. The month has hours left, and the
    // rent for September has not gone out.
    const now = Date.UTC(2026, 7, 31, 16, 0);
    const months = completeMonths({ now, timeZone: 'Asia/Kolkata', count: 1 });

    assert.deepStrictEqual(months.map((month) => month.key), ['2026-07']);
  });

  ok('the first instant of a month belongs to that month', () => {
    // An off-by-one here would credit January with December's figures.
    const firstInstant = startOfDayInstant(2026, 1, 1, 'Asia/Kolkata');

    assert.strictEqual(monthContaining(firstInstant, 'Asia/Kolkata').key, '2026-01');
  });

  ok('a window crossing a year boundary counts back correctly', () => {
    const now = Date.UTC(2026, 1, 10, 6, 0);
    const months = completeMonths({ now, timeZone: 'Asia/Kolkata', count: 4 });

    assert.deepStrictEqual(months.map((month) => month.key),
      ['2025-10', '2025-11', '2025-12', '2026-01']);
  });

  ok('a zero or negative month count is refused rather than returning nothing', () => {
    const now = Date.UTC(2026, 7, 17);

    assert.throws(() => completeMonths({ now, timeZone: 'Asia/Kolkata', count: 0 }), /positive/);
  });

  console.log('\nreporting');

  ok('the excluded partial month says how far through it is', () => {
    const now = Date.UTC(2026, 7, 16, 18, 30); // 17 August 00:00 IST — 16 days in.
    const partial = currentPartialMonth({ now, timeZone: 'Asia/Kolkata' });

    assert.strictEqual(partial.key, '2026-08');
    assert.ok(partial.fractionElapsed > 0.5 && partial.fractionElapsed < 0.55,
      `expected roughly half the month elapsed, got ${partial.fractionElapsed}`);
  });

  ok('a window states the span it covers', () => {
    const months = completeMonths({
      now: Date.UTC(2026, 7, 17), timeZone: 'Asia/Kolkata', count: 6
    });
    const window = windowOf(months);

    assert.strictEqual(window.firstKey, '2026-02');
    assert.strictEqual(window.lastKey, '2026-07');
    assert.strictEqual(window.monthCount, 6);
    assert.strictEqual(window.start, months[0].start);
  });

  ok('month arithmetic rolls across years in both directions', () => {
    assert.deepStrictEqual(addMonths({ year: 2026, month: 1 }, -1), { year: 2025, month: 12 });
    assert.deepStrictEqual(addMonths({ year: 2026, month: 12 }, 1), { year: 2027, month: 1 });
    assert.deepStrictEqual(addMonths({ year: 2026, month: 6 }, -18), { year: 2024, month: 12 });
  });

  console.log('\nall period tests passed');
})();
