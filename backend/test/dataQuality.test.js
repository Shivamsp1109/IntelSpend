/**
 * Data quality as part of the answer.
 *
 * Run with: npm test
 *
 * The case these exist for is the user who has recorded spending and goals but
 * no income. One blocking switch would refuse the whole assessment and show them
 * an app that appears to know nothing about them, when it can answer what they
 * have saved towards a goal perfectly well. Each test pins either a question
 * that must stay answerable, or one that must genuinely refuse.
 */
const assert = require('assert');
const { buildObservedState, sourceWatermarks } = require('../src/engine/financialState');
const { assessDataQuality, CONFIDENCE } = require('../src/engine/dataQuality');
const { READINESS, COMPONENT } = require('../src/engine/readiness');

function ok(name, fn) {
  fn();
  console.log(`  ok  ${name}`);
}

const NOW = Date.UTC(2026, 7, 17);
const PERIOD = { start: Date.UTC(2026, 7, 1), end: Date.UTC(2026, 7, 31) };
const daysAgo = (days) => NOW - days * 86_400_000;

const expense = (id, amount, date = daysAgo(5)) => ({
  id, local_id: id, amount, currency: 'INR', nature: 'Spending',
  category: 'Other', expense_date: date
});

const income = (id, amount, date = daysAgo(10)) => ({
  id, local_id: id, amount, currency: 'INR', source: 'SALARY', income_date: date
});

const commitment = (localId, amount, overrides = {}) => ({
  local_id: localId, title: `Commitment ${localId}`, amount, cadence: 'MONTHLY',
  currency: 'INR', nature: 'Spending', category: 'RentHousing', status: 'ACTIVE',
  last_occurrence_date: daysAgo(12), next_due_date: NOW + 18 * 86_400_000,
  pending_amount: null, ...overrides
});

const goal = (localId) => ({
  local_id: localId, type: 'VACATION', target_amount: 60000,
  target_date: NOW + 180 * 86_400_000, current_saved: 20000, monthly_contribution: 5000
});

function assess(partialRows, watermarkArgs = { pendingLocalChanges: 0, lastSuccessfulSyncAt: NOW }) {
  const rows = { expenses: [], incomes: [], recurring: [], goals: [], ...partialRows };
  const observedState = buildObservedState({
    rows, currency: 'INR', period: PERIOD, timezone: 'Asia/Kolkata', now: NOW
  });
  const watermarks = sourceWatermarks({ ...watermarkArgs, now: NOW });
  return assessDataQuality({ rows, observedState, watermarks, now: NOW });
}

(async () => {
  console.log('data quality');

  ok('missing income blocks cash flow but leaves goal progress answerable', () => {
    // The headline case for the whole per-component design.
    const quality = assess({ expenses: [expense(1, 40000)], goals: [goal(1)] });

    assert.strictEqual(quality.readiness[COMPONENT.CASH_FLOW].status, READINESS.BLOCKED);
    assert.strictEqual(quality.readiness[COMPONENT.AFFORDABILITY].status, READINESS.BLOCKED);
    assert.strictEqual(quality.readiness[COMPONENT.GOAL_PROGRESS].status, READINESS.READY);
  });

  ok('a blocked component says what it needs, in words a user would recognise', () => {
    const quality = assess({ expenses: [expense(1, 40000)], goals: [goal(1)] });

    assert.ok(
      quality.caveats.some((caveat) =>
        caveat.includes('Cash flow') && caveat.includes('recorded income')),
      `expected a plain-language cash flow caveat, got: ${JSON.stringify(quality.caveats)}`
    );
  });

  ok('domains with no store yet block rather than vanishing', () => {
    // Assets and insurance arrive in later stages. Until then the components
    // that need them must report BLOCKED with a nameable reason, not silently
    // drop out of the readiness map.
    const quality = assess({ expenses: [expense(1, 40000)], incomes: [income(1, 100000)] });

    assert.strictEqual(quality.readiness[COMPONENT.NET_WORTH].status, READINESS.BLOCKED);
    assert.strictEqual(quality.readiness[COMPONENT.INSURANCE_GAP].status, READINESS.BLOCKED);
    assert.ok(quality.readiness[COMPONENT.NET_WORTH].missing.includes('assets'));
  });

  ok('a full-looking month with everything recorded reads as ready', () => {
    const quality = assess({
      expenses: [expense(1, 40000)],
      incomes: [income(1, 100000)],
      recurring: [commitment(1, 22000)],
      goals: [goal(1)]
    });

    assert.strictEqual(quality.readiness[COMPONENT.CASH_FLOW].status, READINESS.READY);
    assert.strictEqual(quality.coverage.income, true);
    assert.strictEqual(quality.coverage.assets, false);
  });

  console.log('\nconsistency');

  ok('a commitment nothing has ever settled is reported, not resolved', () => {
    // Either the commitment is wrong or payments are being missed. Both matter,
    // and the engine cannot tell which without asking.
    const quality = assess({
      expenses: [expense(1, 40000)],
      incomes: [income(1, 100000)],
      recurring: [commitment(1, 22000, { last_occurrence_date: null })]
    });

    const problem = quality.consistency.find((p) => p.kind === 'COMMITMENT_NEVER_SETTLED');
    assert.ok(problem, 'expected the never-settled commitment to be reported');
    assert.deepStrictEqual(problem.localIds, [1]);
  });

  ok('an unanswered price change is surfaced as a limitation', () => {
    const quality = assess({
      expenses: [expense(1, 40000)],
      incomes: [income(1, 100000)],
      recurring: [commitment(1, 199, { pending_amount: 139 })]
    });

    const problem = quality.consistency.find((p) => p.kind === 'PRICE_CHANGE_UNANSWERED');
    assert.ok(problem);
    assert.ok(problem.detail.includes('amount they agreed'));
  });

  console.log('\nconfidence and staleness');

  ok('unsynced device changes drop confidence and say so', () => {
    // The figures may be arithmetically perfect and still describe only part of
    // what the user recorded, which outranks every other quality signal.
    const quality = assess(
      {
        expenses: [expense(1, 40000)],
        incomes: [income(1, 100000)],
        recurring: [commitment(1, 22000)],
        goals: [goal(1)]
      },
      { pendingLocalChanges: 3, lastSuccessfulSyncAt: daysAgo(1) }
    );

    assert.strictEqual(quality.confidence, CONFIDENCE.LOW);
    assert.ok(quality.caveats.some((caveat) => caveat.includes('3 change(s)')));
  });

  ok('a device that reported nothing is treated as unknown, not current', () => {
    const quality = assess(
      { expenses: [expense(1, 40000)], incomes: [income(1, 100000)] },
      {}
    );

    assert.ok(
      quality.caveats.some((caveat) => caveat.includes('did not report')),
      'expected a caveat about the device not reporting its sync state'
    );
  });

  ok('stale data degrades a component rather than blocking it', () => {
    // Four-month-old spending is worth less but is not nothing; the figure
    // stands with a caveat rather than being withheld.
    const quality = assess({
      expenses: [expense(1, 40000, daysAgo(120))],
      incomes: [income(1, 100000, daysAgo(120))]
    });

    assert.strictEqual(quality.readiness[COMPONENT.CASH_FLOW].status, READINESS.DEGRADED);
    assert.ok(quality.caveats.some((caveat) => caveat.includes('out of date')));
  });

  ok('an empty account is unknown rather than confidently zero', () => {
    const quality = assess({});

    assert.strictEqual(quality.confidence, CONFIDENCE.UNKNOWN);
    assert.strictEqual(quality.completeness.held, 0);
  });

  console.log('\nall data quality tests passed');
})();
