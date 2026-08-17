/**
 * Money as a whole number of the currency's smallest unit.
 *
 * The engine's counterpart to the app's MoneyAmount, and it exists for the same
 * reason: `0.1 + 0.2 !== 0.3` in JavaScript exactly as it does in Kotlin, so a
 * column of figures that each look right sums to something a user can see is
 * wrong, and two amounts that should match compare unequal. Neither failure
 * announces itself.
 *
 * Whole minor units remove the problem instead of managing it. Addition and
 * subtraction are exact, equality means equality, and rounding happens once, at
 * the boundary where a decimal enters, rather than repeatedly and invisibly.
 *
 * Values are plain `{ minorUnits, currency }` objects rather than a class, so
 * they serialise into a snapshot payload and back without a revival step. They
 * are treated as immutable — every operation returns a new one.
 *
 * No decimal library. Personal-finance figures in paise fit inside JavaScript's
 * safe integer range with room to spare (₹90,071,992,547,409 before it becomes a
 * concern), and every operation here checks that bound rather than assuming it.
 */

/**
 * ISO 4217 exponents for the currencies the app supports.
 *
 * Carried rather than assumed to be two: yen has no minor unit, and a hardcoded
 * 100 would inflate every yen figure a hundredfold into a number that still
 * looks plausible.
 */
const MINOR_UNIT_DIGITS = {
  INR: 2, USD: 2, EUR: 2, GBP: 2, JPY: 0,
  AED: 2, SGD: 2, CAD: 2, AUD: 2, CHF: 2
};

function minorUnitDigits(currency) {
  const digits = MINOR_UNIT_DIGITS[currency];
  if (digits === undefined) {
    throw new Error(`Unknown currency '${currency}'; refusing to guess its scale.`);
  }
  return digits;
}

function assertSafe(minorUnits) {
  if (!Number.isSafeInteger(minorUnits)) {
    throw new Error(
      `Money figure ${minorUnits} is outside the exact-integer range; ` +
      'the result would be silently approximate.'
    );
  }
  return minorUnits;
}

function money(minorUnits, currency) {
  minorUnitDigits(currency);
  return Object.freeze({ minorUnits: assertSafe(minorUnits), currency });
}

function zero(currency) {
  return money(0, currency);
}

function assertSameCurrency(a, b) {
  if (a.currency !== b.currency) {
    throw new Error(
      `Cannot combine ${a.currency} with ${b.currency}; ` +
      'this product has no exchange rate source.'
    );
  }
}

function add(a, b) {
  assertSameCurrency(a, b);
  return money(a.minorUnits + b.minorUnits, a.currency);
}

function subtract(a, b) {
  assertSameCurrency(a, b);
  return money(a.minorUnits - b.minorUnits, a.currency);
}

function multiply(amount, count) {
  if (!Number.isInteger(count)) {
    throw new Error(`multiply expects a whole count, got ${count}; use scaleBy for rates.`);
  }
  return money(amount.minorUnits * count, amount.currency);
}

function negate(amount) {
  return money(-amount.minorUnits, amount.currency);
}

function compare(a, b) {
  assertSameCurrency(a, b);
  return a.minorUnits === b.minorUnits ? 0 : (a.minorUnits < b.minorUnits ? -1 : 1);
}

function equals(a, b) {
  return a.currency === b.currency && a.minorUnits === b.minorUnits;
}

const isZero = (amount) => amount.minorUnits === 0;
const isNegative = (amount) => amount.minorUnits < 0;
const isPositive = (amount) => amount.minorUnits > 0;

function coerceAtLeastZero(amount) {
  return isNegative(amount) ? zero(amount.currency) : amount;
}

/**
 * Rounds half-even, ties going to the even neighbour.
 *
 * Not half-up, which rounds away from zero every time and so drifts upward
 * across a long run of calculations — a three-hundred-month projection
 * compounds that bias into a figure that is visibly wrong. Half-even sends ties
 * both ways, so they cancel.
 */
function roundHalfEven(value) {
  const floor = Math.floor(value);
  const remainder = value - floor;

  if (remainder > 0.5) return floor + 1;
  if (remainder < 0.5) return floor;
  return floor % 2 === 0 ? floor : floor + 1;
}

/**
 * Scales by a rate — a growth assumption, a share, an interest rate.
 *
 * The only operation here that has to round, so it says how.
 */
function scaleBy(amount, factor) {
  if (!Number.isFinite(factor)) {
    throw new Error(`Cannot scale money by ${factor}.`);
  }
  return money(roundHalfEven(amount.minorUnits * factor), amount.currency);
}

/**
 * One amount as a fraction of another — a savings rate, a debt ratio.
 *
 * Null rather than zero or Infinity when the denominator is zero: "what share
 * of no income did you save" has no answer, and any number returned there would
 * be a claim the data does not support.
 */
function ratio(numerator, denominator) {
  assertSameCurrency(numerator, denominator);
  if (denominator.minorUnits === 0) return null;
  return numerator.minorUnits / denominator.minorUnits;
}

function sum(amounts, currency) {
  return amounts.reduce((running, next) => add(running, next), zero(currency));
}

/**
 * The decimal form, for the DECIMAL columns the engine's tables use.
 *
 * Built by string surgery rather than division, so the value never passes
 * through a float on its way to the database.
 */
function toDecimalString(amount) {
  const digits = minorUnitDigits(amount.currency);
  const negative = amount.minorUnits < 0;
  const absolute = Math.abs(amount.minorUnits).toString();

  if (digits === 0) return `${negative ? '-' : ''}${absolute}`;

  const padded = absolute.padStart(digits + 1, '0');
  const whole = padded.slice(0, padded.length - digits);
  const fraction = padded.slice(padded.length - digits);
  return `${negative ? '-' : ''}${whole}.${fraction}`;
}

/**
 * Reads a DECIMAL column, which mysql2 hands back as a string.
 *
 * Also parsed by string surgery: `Number('199.99') * 100` is 19998.999999999996,
 * which truncates to a paisa less than the user was charged.
 */
function fromDecimalString(value, currency) {
  const digits = minorUnitDigits(currency);
  const text = String(value).trim();

  if (!/^-?\d+(\.\d+)?$/.test(text)) {
    throw new Error(`'${value}' is not a decimal figure.`);
  }

  const negative = text.startsWith('-');
  const [whole, fraction = ''] = (negative ? text.slice(1) : text).split('.');

  // One extra digit is kept so the discarded remainder can decide the rounding
  // rather than being truncated away unseen.
  const keptFraction = fraction.slice(0, digits).padEnd(digits, '0');
  const remainder = fraction.slice(digits);

  let minorUnits = Number(`${whole}${keptFraction}`);
  if (remainder.length > 0) {
    const firstDropped = Number(remainder[0]);
    const restNonZero = /[1-9]/.test(remainder.slice(1));
    const roundsUp = firstDropped > 5 ||
      (firstDropped === 5 && (restNonZero || minorUnits % 2 !== 0));
    if (roundsUp) minorUnits += 1;
  }

  return money(negative ? -minorUnits : minorUnits, currency);
}

/**
 * Reads a legacy DOUBLE column into minor units.
 *
 * The existing expenses/incomes/goals/recurring tables are all DOUBLE, so every
 * figure entering the engine from them comes through here. `toFixed` goes via
 * the shortest decimal that round-trips the double, so a stored 199.99 reads as
 * 199.99 rather than the 199.99000000000000909 its binary form literally holds.
 */
function fromLegacyDouble(value, currency) {
  if (!Number.isFinite(value)) {
    throw new Error(`Cannot read ${value} as money.`);
  }
  return fromDecimalString(value.toFixed(minorUnitDigits(currency) + 2), currency);
}

module.exports = {
  money,
  zero,
  add,
  subtract,
  multiply,
  negate,
  compare,
  equals,
  isZero,
  isNegative,
  isPositive,
  coerceAtLeastZero,
  scaleBy,
  ratio,
  sum,
  toDecimalString,
  fromDecimalString,
  fromLegacyDouble,
  minorUnitDigits
};
