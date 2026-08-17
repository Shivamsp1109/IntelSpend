/**
 * The cash-flow engine.
 *
 * Run with: npm test
 *
 * Two failures are worth more protection than the rest. A part-month slipping
 * into the baseline is wrong in the flattering direction — salary in, rent not
 * yet out — so it reports someone as comfortable when they are not, and looks
 * entirely plausible while doing it. Silently dropping a foreign-currency row
 * does the same thing from the other side: the income vanishes, the savings rate
 * moves, and nothing on screen says a figure was left out.
 */
const assert = require('assert');
const money = require('../src/engine/money');
const periods = require('../src/engine/periods');
const {
  assessCashFlow,
  summariseObligations,
  stabilityOf,
  median,
  assertSingleCurrency,
  STABILITY
} = require('../src/engine/cashFlowEngine');

function ok(name, fn) {
  fn();
  console.log(`  ok  ${name}`);
}

const ZONE = 'Asia/Kolkata';
// 17 August 2026, mid-morning in India. Deliberately mid-month: a baseline that
// leaks the current month shows up immediately.
const NOW = Date.UTC(2026, 7, 17, 6, 0);

/** A day inside a named month, in the user's zone. */
const dayIn = (year, month, day) =>
  periods.startOfDayInstant(year, month, day, ZONE) + 10 * 3_600_000;

const income = (id, amount, year, month, day = 1, currency = 'INR') => ({
  id, local_id: id, amount, currency, source: 'SALARY', income_date: dayIn(year, month, day)
});

const expense = (id, amount, year, month, day = 5, nature = 'Spending', currency = 'INR') => ({
  id, local_id: id, amount, currency, nature, category: 'Other',
  expense_date: dayIn(year, month, day)
});

const commitment = (localId, amount, overrides = {}) => ({
  local_id: localId, title: `Commitment ${localId}`, amount, cadence: 'MONTHLY',
  currency: 'INR', nature: 'Spending', category: 'RentHousing', status: 'ACTIVE',
  last_occurrence_date: NOW - 12 * 86_400_000, next_due_date: NOW + 18 * 86_400_000,
  pending_amount: null, ...overrides
});

const assess = (partial, monthsBack = 6) => assessCashFlow({
  rows: { expenses: [], incomes: [], recurring: [], ...partial },
  currency: 'INR',
  now: NOW,
  timezone: ZONE,
  monthsBack
});

/** Salary and spending in each of the six complete months before August. */
function steadySixMonths() {
  const incomes = [];
  const expenses = [];
  let id = 1;
  for (let month = 2; month <= 7; month += 1) {
    incomes.push(income(id, 100000, 2026, month));
    expenses.push(expense(id + 100, 60000, 2026, month));
    id += 1;
  }
  return { incomes, expenses };
}

(async () => {
  console.log('complete periods only');

  ok('the current partial month never reaches the baseline', () => {
    // August is half over: the salary has landed, most of the month's spending
    // has not. Counted, it would report a far higher surplus than is real.
    const rows = steadySixMonths();
    rows.incomes.push(income(999, 100000, 2026, 8, 1));

    const result = assess(rows);

    assert.strictEqual(result.window.lastKey, '2026-07');
    assert.strictEqual(result.excludedPartialMonth.key, '2026-08');
    assert.strictEqual(result.income.baseline.minorUnits, 10000000);
    assert.ok(result.income.byMonth.every((entry) => entry.key !== '2026-08'));
  });

  ok('the window covering the figures is stated', () => {
    const result = assess(steadySixMonths());

    assert.strictEqual(result.window.firstKey, '2026-02');
    assert.strictEqual(result.window.lastKey, '2026-07');
    assert.strictEqual(result.window.monthCount, 6);
  });

  ok('one month is a period, not a trend', () => {
    const result = assess({
      incomes: [income(1, 100000, 2026, 7)],
      expenses: [expense(2, 60000, 2026, 7)]
    }, 1);

    assert.strictEqual(result.monthsObserved, 1);
    assert.strictEqual(result.trendIsMeaningful, false);
    assert.ok(result.caveats.some((caveat) => caveat.includes('not yet a pattern')));
  });

  ok('three complete months is enough to call a trend', () => {
    const result = assess({
      incomes: [income(1, 100000, 2026, 5), income(2, 100000, 2026, 6), income(3, 100000, 2026, 7)],
      expenses: [expense(4, 60000, 2026, 5), expense(5, 60000, 2026, 6), expense(6, 60000, 2026, 7)]
    }, 3);

    assert.strictEqual(result.trendIsMeaningful, true);
  });

  console.log('\nbaselines');

  ok('an annual premium does not distort the ordinary month', () => {
    // ₹60,000 of normal spending, plus a ₹1,20,000 insurance premium in one
    // month. A mean reports ₹80,000 a month, which the user never spends.
    const rows = steadySixMonths();
    rows.expenses.push(expense(500, 120000, 2026, 4, 20));

    const result = assess(rows);

    assert.strictEqual(result.outflow.baseline.minorUnits, 6000000);
    assert.ok(result.outflow.mean.minorUnits > result.outflow.baseline.minorUnits);
  });

  ok('a month with no records is left out, not counted as zero', () => {
    // Averaging over an unimported month drags the baseline down by however
    // many months the user simply has not got round to yet.
    const rows = steadySixMonths();
    rows.incomes = rows.incomes.filter((row) => row.income_date < dayIn(2026, 4, 1) ||
      row.income_date > dayIn(2026, 4, 28));
    rows.expenses = rows.expenses.filter((row) => row.expense_date < dayIn(2026, 4, 1) ||
      row.expense_date > dayIn(2026, 4, 28));

    const result = assess(rows);

    assert.deepStrictEqual(result.monthsWithoutData, ['2026-04']);
    assert.strictEqual(result.monthsObserved, 5);
    assert.strictEqual(result.income.baseline.minorUnits, 10000000);
    assert.ok(result.caveats.some((caveat) => caveat.includes('2026-04')));
  });

  ok('the median of an even number of months averages the middle two', () => {
    const amounts = [10000, 20000, 30000, 40000].map((v) => money.money(v, 'INR'));

    assert.strictEqual(median(amounts, 'INR').minorUnits, 25000);
  });

  ok('savings rate is null when nothing came in, never zero', () => {
    const result = assess({ expenses: [expense(1, 60000, 2026, 7)] }, 1);

    assert.strictEqual(result.savingsRate, null);
    assert.ok(result.caveats.some((caveat) => caveat.includes('savings rate cannot')));
  });

  console.log('\nincome stability');

  ok('a steady salary reads as stable', () => {
    const result = assess(steadySixMonths());

    assert.strictEqual(result.income.stability.band, STABILITY.STABLE);
  });

  ok('freelance income is flagged as volatile, not blocked', () => {
    // Irregular income is a fact about the work, not a data problem. Refusing
    // to assess would lock out the people whose cash flow is hardest to plan.
    const incomes = [
      income(1, 20000, 2026, 2), income(2, 180000, 2026, 3), income(3, 40000, 2026, 4),
      income(4, 150000, 2026, 5), income(5, 30000, 2026, 6), income(6, 90000, 2026, 7)
    ];
    const result = assess({ incomes, expenses: steadySixMonths().expenses });

    assert.strictEqual(result.income.stability.band, STABILITY.VOLATILE);
    assert.ok(result.income.baseline.minorUnits > 0, 'a baseline is still produced');
    assert.ok(result.caveats.some((caveat) => caveat.includes('varies a lot')));
  });

  ok('stability is unknown from a single month rather than guessed', () => {
    const single = [money.money(100000, 'INR')];

    assert.strictEqual(stabilityOf(single).band, STABILITY.UNKNOWN);
    assert.strictEqual(stabilityOf(single).coefficientOfVariation, null);
  });

  console.log('\ncurrency isolation');

  ok('a mixed-currency income row throws rather than being skipped', () => {
    // Silently dropping it would understate what the user earns, and the
    // resulting savings rate would look entirely reasonable.
    assert.throws(
      () => assess({
        incomes: [income(1, 100000, 2026, 7), income(2, 500, 2026, 7, 1, 'USD')],
        expenses: [expense(3, 60000, 2026, 7)]
      }, 1),
      /USD.*INR|no exchange rate source/s
    );
  });

  ok('a mixed-currency expense row throws too', () => {
    assert.throws(
      () => assess({
        incomes: [income(1, 100000, 2026, 7)],
        expenses: [expense(2, 60000, 2026, 7), expense(3, 40, 2026, 7, 5, 'Spending', 'EUR')]
      }, 1),
      /EUR/
    );
  });

  ok('a live commitment in another currency throws', () => {
    assert.throws(
      () => summariseObligations([commitment(1, 199, { currency: 'USD' })], 'INR', NOW),
      /USD/
    );
  });

  ok('the guard names the offending currency and why it refuses', () => {
    assert.throws(
      () => assertSingleCurrency([{ currency: 'GBP' }], 'INR', 'Income rows'),
      (error) => error.message.includes('GBP') &&
        error.message.includes('no exchange rate source')
    );
  });

  console.log('\nstanding obligations');

  ok('obligations are cadence-normalised and split by kind', () => {
    const result = summariseObligations([
      commitment(1, 22000, { nature: 'Spending' }),
      commitment(2, 15000, { nature: 'LoanRepayment' }),
      commitment(3, 12000, { cadence: 'YEARLY' })
    ], 'INR', NOW);

    // 22,000 + 15,000 + 1,000 a month.
    assert.strictEqual(result.monthlyTotal.minorUnits, 3800000);
    assert.strictEqual(result.monthlyDebt.minorUnits, 1500000);
  });

  ok('what is left after obligations is reported separately from surplus', () => {
    const result = assess({
      ...steadySixMonths(),
      recurring: [commitment(1, 22000)]
    });

    assert.strictEqual(result.surplus.minorUnits, 4000000);
    assert.strictEqual(result.obligations.uncommittedSurplus.minorUnits, 1800000);
  });

  ok('a payment past due with nothing recorded is surfaced, not assumed', () => {
    // The engine cannot tell a missed payment from one paid but not imported,
    // and assuming either would be a claim about the user's finances.
    const overdueAt = NOW - 20 * 86_400_000;
    const result = assess({
      ...steadySixMonths(),
      recurring: [commitment(1, 22000, { next_due_date: overdueAt })]
    });

    assert.strictEqual(result.obligations.overdue.length, 1);
    assert.strictEqual(result.obligations.overdue[0].daysOverdue, 20);
    assert.ok(result.caveats.some((caveat) => caveat.includes('past due')));
  });

  ok('a paused commitment is neither owed nor reported overdue', () => {
    const result = summariseObligations([
      commitment(1, 22000, { status: 'PAUSED', next_due_date: NOW - 40 * 86_400_000 })
    ], 'INR', NOW);

    assert.strictEqual(result.monthlyTotal.minorUnits, 0);
    assert.strictEqual(result.overdue.length, 0);
  });

  console.log('\nnon-spending movements');

  ok('transfers and card bill payments stay out of the outflow baseline', () => {
    const rows = steadySixMonths();
    rows.expenses.push(
      expense(700, 50000, 2026, 5, 8, 'SelfTransfer'),
      expense(701, 30000, 2026, 5, 9, 'CreditCardPayment')
    );

    const result = assess(rows);

    assert.strictEqual(result.outflow.baseline.minorUnits, 6000000);
  });

  console.log('\nempty account');

  ok('no data at all produces no baseline and says why', () => {
    const result = assess({});

    assert.strictEqual(result.monthsObserved, 0);
    assert.strictEqual(result.trendIsMeaningful, false);
    assert.ok(result.caveats.some((caveat) => caveat.includes('No complete month')));
  });

  console.log('\nall cash flow tests passed');
})();
