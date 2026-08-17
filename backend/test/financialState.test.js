/**
 * The canonical observed state.
 *
 * Run with: npm test
 *
 * Facts only — what the engine can say happened, before any assumption is
 * applied. The cases below pin the places where a plausible-looking shortcut
 * produces a figure that is wrong in a way nobody would catch by reading it: a
 * transfer counted as spending, a weekly commitment added as if it were monthly,
 * a price the user never agreed to, or a hash that moves when nothing did.
 */
const assert = require('assert');
const money = require('../src/engine/money');
const {
  buildObservedState,
  summariseCommitments,
  sourceWatermarks,
  canonicalise,
  hashOf
} = require('../src/engine/financialState');

function ok(name, fn) {
  fn();
  console.log(`  ok  ${name}`);
}

const NOW = Date.UTC(2026, 7, 17);
const PERIOD = { start: Date.UTC(2026, 7, 1), end: Date.UTC(2026, 7, 31) };
const daysAgo = (days) => NOW - days * 86_400_000;

const expense = (id, amount, nature = 'Spending', currency = 'INR') => ({
  id, local_id: id, amount, currency, nature, category: 'Other', expense_date: daysAgo(5)
});

const income = (id, amount, currency = 'INR') => ({
  id, local_id: id, amount, currency, source: 'SALARY', income_date: daysAgo(10)
});

const commitment = (localId, amount, overrides = {}) => ({
  local_id: localId,
  title: `Commitment ${localId}`,
  amount,
  cadence: 'MONTHLY',
  currency: 'INR',
  nature: 'Spending',
  category: 'RentHousing',
  status: 'ACTIVE',
  last_occurrence_date: daysAgo(12),
  next_due_date: NOW + 18 * 86_400_000,
  pending_amount: null,
  ...overrides
});

const goal = (localId, target, saved) => ({
  local_id: localId,
  type: 'VACATION',
  target_amount: target,
  target_date: NOW + 180 * 86_400_000,
  current_saved: saved,
  monthly_contribution: 5000
});

const build = (rows, currency = 'INR') => buildObservedState({
  rows: { expenses: [], incomes: [], recurring: [], goals: [], ...rows },
  currency,
  period: PERIOD,
  timezone: 'Asia/Kolkata',
  now: NOW
});

(async () => {
  console.log('observed state');

  ok('outflow is split by nature rather than summed into one total', () => {
    const state = build({
      expenses: [
        expense(1, 40000, 'Spending'),
        expense(2, 15000, 'LoanRepayment'),
        expense(3, 20000, 'Investment')
      ]
    });

    assert.strictEqual(state.outflow.byNature.Spending.minorUnits, 4000000);
    assert.strictEqual(state.outflow.byNature.LoanRepayment.minorUnits, 1500000);
    assert.strictEqual(state.outflow.byNature.Investment.minorUnits, 2000000);
    assert.strictEqual(state.outflow.total.minorUnits, 7500000);
  });

  ok('a transfer between the user\'s own accounts never reaches the totals', () => {
    // A statement import picks these up as debits. Counted, one ₹50,000
    // transfer makes the month look ruinous.
    const state = build({
      expenses: [
        expense(1, 40000, 'Spending'),
        expense(2, 50000, 'SelfTransfer'),
        expense(3, 30000, 'CreditCardPayment'),
        expense(4, 5000, 'CashWithdrawal')
      ]
    });

    assert.strictEqual(state.outflow.total.minorUnits, 4000000);
    assert.strictEqual(state.outflow.transactionCount, 1);
  });

  ok('another currency is excluded from the totals and named', () => {
    const state = build({
      expenses: [expense(1, 40000, 'Spending'), expense(2, 500, 'Spending', 'USD')],
      incomes: [income(1, 100000)]
    });

    assert.strictEqual(state.outflow.total.minorUnits, 4000000);
    assert.deepStrictEqual(state.excludedCurrencies, ['USD']);
  });

  ok('commitments are cadence-normalised before being added together', () => {
    // ₹1,000 a week and ₹12,000 a year are not ₹13,000 a month.
    const result = summariseCommitments([
      commitment(1, 1000, { cadence: 'WEEKLY' }),
      commitment(2, 12000, { cadence: 'YEARLY' })
    ], 'INR');

    // 1000 × 52.1775/12 = 4348.13, plus 1000.
    assert.ok(Math.abs(result.monthlyTotal.minorUnits - 534813) < 100);
  });

  ok('paused and ended commitments are not counted as owed', () => {
    const result = summariseCommitments([
      commitment(1, 22000),
      commitment(2, 9000, { status: 'PAUSED' }),
      commitment(3, 5000, { status: 'ENDED' })
    ], 'INR');

    assert.strictEqual(result.monthlyTotal.minorUnits, 2200000);
    assert.strictEqual(result.live.length, 1);
  });

  ok('debt commitments are tracked apart from the rest', () => {
    const result = summariseCommitments([
      commitment(1, 22000, { nature: 'Spending' }),
      commitment(2, 15000, { nature: 'LoanRepayment' })
    ], 'INR');

    assert.strictEqual(result.monthlyTotal.minorUnits, 3700000);
    assert.strictEqual(result.monthlyDebt.minorUnits, 1500000);
  });

  ok('an unanswered price change keeps the amount the user agreed to', () => {
    // The user was asked and has not answered. Using the new figure would
    // decide for them.
    const result = summariseCommitments(
      [commitment(1, 199, { pending_amount: 139 })],
      'INR'
    );

    assert.strictEqual(result.monthlyTotal.minorUnits, 19900);
    assert.strictEqual(result.awaitingPriceDecision.length, 1);
    assert.strictEqual(result.awaitingPriceDecision[0].proposed.minorUnits, 13900);
  });

  ok('a goal carries the contribution the user committed to, untouched', () => {
    const state = build({ goals: [goal(1, 60000, 20000)] });
    const assessed = state.goals.items[0];

    assert.strictEqual(assessed.targetAmount.minorUnits, 6000000);
    assert.strictEqual(assessed.currentSaved.minorUnits, 2000000);
    assert.strictEqual(assessed.monthlyContribution.minorUnits, 500000);
  });

  ok('confirmed and imported figures carry different provenance', () => {
    const state = build({
      expenses: [expense(1, 40000)],
      recurring: [commitment(1, 22000)],
      goals: [goal(1, 60000, 0)]
    });

    assert.strictEqual(state.outflow.provenance.sourceType, 'IMPORTED');
    assert.strictEqual(state.commitments.provenance.sourceType, 'CONFIRMED');
    assert.strictEqual(state.goals.provenance.sourceType, 'CONFIRMED');
  });

  ok('provenance names the rows a figure rests on', () => {
    const state = build({ expenses: [expense(7, 40000), expense(9, 1000)] });

    assert.deepStrictEqual(state.outflow.provenance.sourceRecordIds, [7, 9]);
  });

  console.log('\nsnapshot hashing');

  ok('identical state hashes identically', () => {
    const rows = { expenses: [expense(1, 40000)], incomes: [income(1, 100000)] };

    assert.strictEqual(hashOf(build(rows)), hashOf(build(rows)));
  });

  ok('key order does not change the hash', () => {
    // Two states holding the same figures assembled in a different order would
    // otherwise look like a change nobody made.
    const a = { income: 100, outflow: 40, currency: 'INR' };
    const b = { currency: 'INR', outflow: 40, income: 100 };

    assert.strictEqual(hashOf(a), hashOf(b));
    assert.deepStrictEqual(Object.keys(canonicalise(b)), ['currency', 'income', 'outflow']);
  });

  ok('a changed figure changes the hash', () => {
    const before = build({ expenses: [expense(1, 40000)] });
    const after = build({ expenses: [expense(1, 40001)] });

    assert.notStrictEqual(hashOf(before), hashOf(after));
  });

  console.log('\nsource watermarks');

  ok('a device reporting pending changes is recorded as incomplete', () => {
    const marks = sourceWatermarks({
      pendingLocalChanges: 3,
      lastSuccessfulSyncAt: daysAgo(1),
      now: NOW
    });

    assert.strictEqual(marks.pendingLocalChanges, 3);
    assert.strictEqual(marks.completenessKnown, true);
    assert.strictEqual(marks.isComplete, false);
  });

  ok('a device reporting nothing pending is recorded as complete', () => {
    const marks = sourceWatermarks({ pendingLocalChanges: 0, lastSuccessfulSyncAt: NOW, now: NOW });

    assert.strictEqual(marks.completenessKnown, true);
    assert.strictEqual(marks.isComplete, true);
  });

  ok('a silent device is unknown, not assumed complete', () => {
    // "Nothing is pending" is a claim. An absent report is not that claim, and
    // treating it as one would let a stale assessment present itself as current.
    const marks = sourceWatermarks({ now: NOW });

    assert.strictEqual(marks.pendingLocalChanges, null);
    assert.strictEqual(marks.completenessKnown, false);
    assert.strictEqual(marks.isComplete, false);
  });

  console.log('\nall observed state tests passed');
})();
