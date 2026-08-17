/**
 * Money as whole minor units.
 *
 * Run with: npm test
 *
 * These are about exactness rather than behaviour. Each case pins a place where
 * plain JavaScript numbers are wrong by an amount small enough that nothing
 * notices until a total disagrees with the rows that produced it — which, in a
 * financial assessment, is the failure that matters most and shows up least.
 */
const assert = require('assert');
const money = require('../src/engine/money');

function ok(name, fn) {
  fn();
  console.log(`  ok  ${name}`);
}

const inr = (decimal) => money.fromDecimalString(decimal, 'INR');

(async () => {
  console.log('engine money');

  ok('adding tenths is exact', () => {
    // 0.1 + 0.2 === 0.30000000000000004 as plain numbers.
    const total = money.add(inr('0.10'), inr('0.20'));

    assert.strictEqual(total.minorUnits, 30);
    assert.strictEqual(money.toDecimalString(total), '0.30');
    assert.ok(money.equals(total, inr('0.30')));
  });

  ok('a long run of small amounts sums exactly', () => {
    // A hundred ₹0.07 charges lands at 7.000000000000005 as plain numbers and
    // compares unequal to 7 — the classic silent reconciliation bug.
    const total = money.sum(Array.from({ length: 100 }, () => inr('0.07')), 'INR');

    assert.strictEqual(total.minorUnits, 700);
    assert.strictEqual(money.toDecimalString(total), '7.00');
  });

  ok('a legacy DOUBLE column reads back as the figure the user typed', () => {
    // 199.99 as a double is really 199.99000000000000909...
    const amount = money.fromLegacyDouble(199.99, 'INR');

    assert.strictEqual(amount.minorUnits, 19999);
    assert.strictEqual(money.toDecimalString(amount), '199.99');
  });

  ok('a decimal is read by digits, never by multiplying a float', () => {
    // The specific trap: Number('199.99') * 100 is 19998.999999999996, which
    // truncates to a paisa less than the user was actually charged. Pinned
    // separately because the round-trip cases above happen to survive that bug.
    assert.strictEqual(inr('199.99').minorUnits, 19999);
    assert.strictEqual(inr('0.29').minorUnits, 29);
    assert.strictEqual(inr('1.005').minorUnits, 100);
    assert.strictEqual(inr('8.87').minorUnits, 887);
  });

  ok('round trips through the legacy DOUBLE boundary unchanged', () => {
    ['0.01', '199.99', '99999999.99', '-450.50'].forEach((decimal) => {
      const original = inr(decimal);
      const asDouble = Number(money.toDecimalString(original));
      assert.ok(
        money.equals(original, money.fromLegacyDouble(asDouble, 'INR')),
        `${decimal} did not survive the double boundary`
      );
    });
  });

  ok('a currency with no minor unit is not inflated a hundredfold', () => {
    // ¥5000 is 5000 yen, not 500,000 — a hardcoded 100 would be wrong here in
    // a way that still looks like a plausible number.
    const yen = money.fromDecimalString('5000', 'JPY');

    assert.strictEqual(yen.minorUnits, 5000);
    assert.strictEqual(money.toDecimalString(yen), '5000');
  });

  ok('an unknown currency is refused rather than assumed to have two decimals', () => {
    assert.throws(() => money.zero('XYZ'), /Unknown currency/);
  });

  ok('mixing currencies throws rather than picking one', () => {
    assert.throws(
      () => money.add(inr('100'), money.fromDecimalString('100', 'USD')),
      /no exchange rate source/
    );
  });

  ok('scaling rounds half-even so a long projection does not drift upward', () => {
    // Both land exactly on a half-paisa. Half-up would round both away from
    // zero; half-even sends one each way so the bias cancels rather than
    // compounding over a three-hundred-month projection.
    assert.strictEqual(money.scaleBy(money.money(5, 'INR'), 0.5).minorUnits, 2);
    assert.strictEqual(money.scaleBy(money.money(7, 'INR'), 0.5).minorUnits, 4);
  });

  ok('reading a figure finer than the currency rounds once, at the boundary', () => {
    assert.strictEqual(inr('0.995').minorUnits, 100);
    assert.strictEqual(inr('1.004').minorUnits, 100);
    assert.strictEqual(inr('0.985').minorUnits, 98);
  });

  ok('a ratio against nothing has no answer', () => {
    // "What share of no income did you save" is unanswerable; zero would be a
    // claim about behaviour the data does not support.
    assert.strictEqual(money.ratio(inr('500'), money.zero('INR')), null);
    assert.ok(Math.abs(money.ratio(inr('250'), inr('1000')) - 0.25) < 0.0001);
  });

  ok('a figure beyond exact integer range is refused, not approximated', () => {
    assert.throws(
      () => money.money(Number.MAX_SAFE_INTEGER + 2, 'INR'),
      /outside the exact-integer range/
    );
  });

  ok('negatives survive subtraction and formatting', () => {
    const deficit = money.subtract(inr('100'), inr('450.50'));

    assert.strictEqual(deficit.minorUnits, -35050);
    assert.strictEqual(money.toDecimalString(deficit), '-350.50');
    assert.ok(money.isNegative(deficit));
    assert.ok(money.isZero(money.coerceAtLeastZero(deficit)));
  });

  ok('a malformed decimal is rejected', () => {
    assert.throws(() => inr('one hundred'), /not a decimal figure/);
    assert.throws(() => inr(''), /not a decimal figure/);
  });

  ok('scaleBy is required for rates; multiply only takes whole counts', () => {
    assert.throws(() => money.multiply(inr('100'), 1.5), /whole count/);
    assert.strictEqual(money.multiply(inr('100'), 3).minorUnits, 30000);
  });

  console.log('\nall money tests passed');
})();
