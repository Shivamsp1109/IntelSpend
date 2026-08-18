/**
 * The goal engine.
 *
 * Run with: npm test
 *
 * The case worth protecting hardest is the inflation gate. "₹20,00,000 for a car
 * in three years" means one thing if it is today's price and another if the user
 * already worked out the 2029 figure, and applying growth to the second
 * overstates the goal by years of compounding — which would tell somebody a
 * perfectly reachable goal is out of reach. The second is the contribution the
 * user committed to, which the engine must never overwrite with its own.
 */
const assert = require('assert');
const money = require('../src/engine/money');
const {
  assessGoals,
  assessGoal,
  targetAtMaturity,
  requiredMonthlyContribution,
  FEASIBILITY,
  AMOUNT_BASIS,
  PRIORITY,
  FLEXIBILITY,
  GOAL_STATUS
} = require('../src/engine/goalEngine');

function ok(name, fn) {
  fn();
  console.log(`  ok  ${name}`);
}

const NOW = Date.UTC(2026, 7, 17);
const MONTHS = 30.436875 * 86_400_000;
const inMonths = (n) => NOW + Math.round(n * MONTHS);

const goalRow = (overrides = {}) => ({
  local_id: 1,
  type: 'VEHICLE',
  target_amount: 2000000,
  target_date: inMonths(36),
  current_saved: 0,
  monthly_contribution: 0,
  currency: 'INR',
  amount_basis: AMOUNT_BASIS.NOMINAL_FUTURE,
  priority: PRIORITY.IMPORTANT,
  flexibility: FLEXIBILITY.BOTH_FLEXIBLE,
  status: GOAL_STATUS.ACTIVE,
  ...overrides
});

const assess = (overrides, availableMonthly = money.money(6000000, 'INR')) =>
  assessGoal({
    goal: goalRow(overrides),
    availableMonthly,
    currency: 'INR',
    now: NOW,
    inflationRate: 0.06
  });

(async () => {
  console.log('the inflation gate');

  ok("a target in today's money is grown to what it will cost", () => {
    const result = assess({ amount_basis: AMOUNT_BASIS.TODAYS_MONEY });

    assert.strictEqual(result.inflationApplied, true);
    // ₹20,00,000 × 1.06³ ≈ ₹23,82,032
    assert.ok(result.targetAtMaturity.minorUnits > 238000000);
    assert.ok(result.targetAtMaturity.minorUnits < 239000000);
  });

  ok('a target already in future money is left exactly as entered', () => {
    // Growing it again would overstate the goal by three years of compounding
    // and could report a reachable goal as out of reach.
    const result = assess({ amount_basis: AMOUNT_BASIS.NOMINAL_FUTURE });

    assert.strictEqual(result.inflationApplied, false);
    assert.strictEqual(result.targetAtMaturity.minorUnits, 200000000);
  });

  ok('a manually fixed figure is never touched', () => {
    const result = assess({ amount_basis: AMOUNT_BASIS.MANUALLY_FIXED });

    assert.strictEqual(result.inflationApplied, false);
    assert.strictEqual(result.targetAtMaturity.minorUnits, 200000000);
    assert.ok(result.basisNote.includes('you fixed'));
  });

  ok('the basis is always explained alongside the figure', () => {
    assert.ok(assess({ amount_basis: AMOUNT_BASIS.TODAYS_MONEY }).basisNote.includes('assumption'));
    assert.ok(assess({ amount_basis: AMOUNT_BASIS.NOMINAL_FUTURE }).basisNote.length > 0);
  });

  ok('a goal whose date has arrived is not grown', () => {
    const result = targetAtMaturity({
      targetAmount: money.money(200000000, 'INR'),
      amountBasis: AMOUNT_BASIS.TODAYS_MONEY,
      monthsRemaining: 0,
      inflationRate: 0.06
    });

    assert.strictEqual(result.inflationApplied, false);
  });

  console.log("\nthe user's own contribution");

  ok('the engine reports what is needed without replacing what was chosen', () => {
    // The standing rule: a suggestion is a suggestion. Overwriting their figure
    // would be the app deciding something on their behalf.
    const result = assess({ monthly_contribution: 20000 });

    assert.strictEqual(result.committedMonthlyContribution.minorUnits, 2000000);
    assert.ok(result.requiredMonthlyContribution.minorUnits > 2000000);
    assert.strictEqual(
      result.contributionGap.minorUnits,
      result.requiredMonthlyContribution.minorUnits - 2000000
    );
  });

  ok('an affordable goal that is under-funded says so plainly', () => {
    const result = assess({ monthly_contribution: 1000 });

    assert.strictEqual(result.feasibility, FEASIBILITY.FEASIBLE);
    assert.ok(result.reasons.some((reason) => reason.includes('committed')));
  });

  ok('committing enough leaves no gap', () => {
    const result = assess({ monthly_contribution: 60000 });

    assert.strictEqual(result.contributionGap.minorUnits, 0);
  });

  console.log('\nfeasibility');

  ok('a goal within the surplus is feasible', () => {
    // ₹20,00,000 over 36 months is ₹55,555 a month against ₹60,000 spare.
    assert.strictEqual(assess({}).feasibility, FEASIBILITY.FEASIBLE);
  });

  ok('a goal beyond the surplus but flexible can be adjusted', () => {
    const result = assess({ target_amount: 5000000 });

    assert.strictEqual(result.feasibility, FEASIBILITY.FEASIBLE_WITH_ADJUSTMENT);
    assert.ok(result.reasons.length > 0);
  });

  ok('adjustments offered are only ones the user said are open', () => {
    // Suggesting a later date for something marked date-fixed is advice that
    // cannot be taken, and makes the rest look equally unconsidered.
    const result = assess({ target_amount: 5000000, flexibility: FLEXIBILITY.AMOUNT_FLEXIBLE });

    assert.ok(result.reasons.every((reason) => !reason.includes('month(s) rather than')));
  });

  ok('a fixed goal beyond the surplus is not feasible, not "adjustable"', () => {
    const result = assess({ target_amount: 5000000, flexibility: FLEXIBILITY.FIXED });

    assert.strictEqual(result.feasibility, FEASIBILITY.NOT_FEASIBLE);
    assert.ok(result.reasons[0].includes('fixed'));
  });

  ok('a fully saved goal reads as achieved', () => {
    const result = assess({ current_saved: 2000000 });

    assert.strictEqual(result.feasibility, FEASIBILITY.ACHIEVED);
    assert.strictEqual(result.requiredMonthlyContribution.minorUnits, 0);
  });

  ok('no surplus baseline means insufficient data, not a verdict', () => {
    const result = assess({}, null);

    assert.strictEqual(result.feasibility, FEASIBILITY.INSUFFICIENT_DATA);
  });

  ok('a paused goal is not assessed', () => {
    const result = assess({ status: GOAL_STATUS.PAUSED });

    assert.strictEqual(result.feasibility, FEASIBILITY.INSUFFICIENT_DATA);
  });

  ok('a goal due this month needs the whole shortfall, not a fraction', () => {
    const required = requiredMonthlyContribution({
      target: money.money(3000000, 'INR'),
      alreadySaved: money.money(0, 'INR'),
      monthsRemaining: 0
    });

    assert.strictEqual(required.minorUnits, 3000000);
  });

  console.log('\nall goals together');

  ok('goals affordable apart can be impossible together', () => {
    const goals = [1, 2, 3].map((id) => goalRow({
      local_id: id, target_amount: 1800000, target_date: inMonths(36)
    }));

    const result = assessGoals({
      goalRows: goals,
      availableMonthly: money.money(6000000, 'INR'),
      currency: 'INR',
      now: NOW
    });

    assert.ok(result.goals.every((goal) => goal.feasibility === FEASIBILITY.FEASIBLE));
    assert.strictEqual(result.collectivelyFeasible, false);
    assert.ok(result.caveats.some((caveat) => caveat.includes('not all of them together')));
  });

  ok('essential goals are listed before nice-to-haves', () => {
    const result = assessGoals({
      goalRows: [
        goalRow({ local_id: 1, priority: PRIORITY.NICE_TO_HAVE }),
        goalRow({ local_id: 2, priority: PRIORITY.ESSENTIAL }),
        goalRow({ local_id: 3, priority: PRIORITY.IMPORTANT })
      ],
      availableMonthly: money.money(6000000, 'INR'),
      currency: 'INR',
      now: NOW
    });

    assert.deepStrictEqual(result.goals.map((goal) => goal.localId), [2, 3, 1]);
  });

  ok('achieved goals do not consume the surplus', () => {
    const result = assessGoals({
      goalRows: [goalRow({ local_id: 1, current_saved: 2000000 })],
      availableMonthly: money.money(6000000, 'INR'),
      currency: 'INR',
      now: NOW
    });

    assert.strictEqual(result.totalRequiredMonthly.minorUnits, 0);
  });

  ok('the inflation rate is labelled an assumption, not a forecast', () => {
    const result = assessGoals({
      goalRows: [goalRow()],
      availableMonthly: money.money(6000000, 'INR'),
      currency: 'INR',
      now: NOW
    });

    assert.strictEqual(result.inflationAssumption.kind, 'ASSUMPTION');
    assert.ok(result.inflationAssumption.note.includes('not a forecast'));
  });

  ok('a goal in another currency throws rather than being counted', () => {
    assert.throws(
      () => assess({ currency: 'USD' }),
      /USD/
    );
  });

  ok('no goals at all says so', () => {
    const result = assessGoals({
      goalRows: [], availableMonthly: money.money(6000000, 'INR'), currency: 'INR', now: NOW
    });

    assert.ok(result.caveats.some((caveat) => caveat.includes('No goals are recorded')));
  });

  console.log('\nall goal engine tests passed');
})();
