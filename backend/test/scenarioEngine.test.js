/**
 * The scenario engine.
 *
 * Run with: npm test
 *
 * Two failures matter more than the rest.
 *
 * If a fact moves between scenarios, the whole separation of observed from
 * projected has quietly collapsed — and it would be invisible, because each run
 * would still look internally consistent. That case is asserted byte for byte.
 *
 * If a near-term goal gets an equity return, the app tells somebody that next
 * year's school fees will be fine because the market will have grown. It might
 * not have. The substitution is forced in the engine and pinned here.
 */
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const money = require('../src/engine/money');
const {
  projectGoalAcrossScenarios,
  projectGoal,
  resolveAssetClass,
  findAssumption,
  futureValueOfContributions,
  horizonBand,
  SCENARIO,
  ASSET_CLASS,
  HORIZON,
  NEAR_TERM_MONTHS
} = require('../src/engine/scenarioEngine');

function ok(name, fn) {
  fn();
  console.log(`  ok  ${name}`);
}

const NOW = Date.UTC(2026, 7, 17);

/** Mirrors what the seeded assumption_sets rows look like once read back. */
const assumption = (overrides) => ({
  id: 1,
  uid: null,
  scenario_type: SCENARIO.BASE,
  asset_class: ASSET_CLASS.NONE,
  time_horizon_band: HORIZON.ANY,
  jurisdiction: 'IN',
  currency: 'INR',
  inflation_rate: null,
  expected_return: null,
  source: 'test',
  approval_status: 'APPROVED',
  ...overrides
});

/** The seeded set, condensed to what these tests exercise. */
const ASSUMPTIONS = [
  assumption({ id: 1, scenario_type: 'CONSERVATIVE', inflation_rate: '7.5000' }),
  assumption({ id: 2, scenario_type: 'BASE', inflation_rate: '6.0000' }),
  assumption({ id: 3, scenario_type: 'OPTIMISTIC', inflation_rate: '4.5000' }),

  assumption({ id: 4, scenario_type: 'CONSERVATIVE', asset_class: 'CASH', expected_return: '3.0000' }),
  assumption({ id: 5, scenario_type: 'BASE', asset_class: 'CASH', expected_return: '4.0000' }),
  assumption({ id: 6, scenario_type: 'OPTIMISTIC', asset_class: 'CASH', expected_return: '6.0000' }),

  assumption({
    id: 7, scenario_type: 'CONSERVATIVE', asset_class: 'EQUITY',
    time_horizon_band: 'MEDIUM', expected_return: '6.0000'
  }),
  assumption({
    id: 8, scenario_type: 'BASE', asset_class: 'EQUITY',
    time_horizon_band: 'MEDIUM', expected_return: '10.0000'
  }),
  assumption({
    id: 9, scenario_type: 'OPTIMISTIC', asset_class: 'EQUITY',
    time_horizon_band: 'MEDIUM', expected_return: '13.0000'
  }),

  assumption({
    id: 13, scenario_type: 'CONSERVATIVE', asset_class: 'EQUITY',
    time_horizon_band: 'LONG', expected_return: '8.0000'
  }),
  assumption({
    id: 14, scenario_type: 'BASE', asset_class: 'EQUITY',
    time_horizon_band: 'LONG', expected_return: '12.0000'
  }),
  assumption({
    id: 15, scenario_type: 'OPTIMISTIC', asset_class: 'EQUITY',
    time_horizon_band: 'LONG', expected_return: '15.0000'
  }),

  assumption({ id: 10, scenario_type: 'CONSERVATIVE', asset_class: 'BLENDED', expected_return: '5.0000' }),
  assumption({ id: 11, scenario_type: 'BASE', asset_class: 'BLENDED', expected_return: '8.0000' }),
  assumption({ id: 12, scenario_type: 'OPTIMISTIC', asset_class: 'BLENDED', expected_return: '11.0000' })
];

const goal = (overrides = {}) => ({
  localId: 1,
  type: 'VEHICLE',
  targetAmount: money.money(200000000, 'INR'),
  alreadySaved: money.money(0, 'INR'),
  monthsRemaining: 36,
  amountBasis: 'TODAYS_MONEY',
  inflateTarget: true,
  assetClass: ASSET_CLASS.EQUITY,
  ...overrides
});

const across = (overrides = {}, contribution = money.money(5000000, 'INR')) =>
  projectGoalAcrossScenarios({
    goal: goal(overrides),
    assumptions: ASSUMPTIONS,
    monthlyContribution: contribution,
    currency: 'INR',
    now: NOW
  });

(async () => {
  console.log('facts do not move between scenarios');

  ok('the observed block is byte-identical across all three runs', () => {
    // The whole separation of observed from projected rests on this, and a
    // failure would be invisible: each run would still look consistent alone.
    const result = across();
    const serialised = JSON.stringify(result.observed);

    const again = across();
    assert.strictEqual(JSON.stringify(again.observed), serialised);
    assert.strictEqual(again.inputsHash, result.inputsHash);
  });

  ok('three scenarios give three different projections', () => {
    const result = across();
    const reached = result.projections.map((p) => p.projectedSavings.minorUnits);

    assert.strictEqual(new Set(reached).size, 3, `expected three distinct outcomes, got ${reached}`);
    // Conservative first, and genuinely the least favourable.
    assert.ok(reached[0] < reached[1] && reached[1] < reached[2]);
  });

  ok('the target itself also varies, because inflation is an assumption too', () => {
    const result = across();
    const targets = result.projections.map((p) => p.targetAtMaturity.minorUnits);

    // Conservative assumes higher inflation, so the goal costs more.
    assert.ok(targets[0] > targets[1] && targets[1] > targets[2]);
  });

  ok('a target already in future money is not grown under any scenario', () => {
    const result = across({ inflateTarget: false, amountBasis: 'NOMINAL_FUTURE' });

    result.projections.forEach((projection) => {
      assert.strictEqual(projection.targetAtMaturity.minorUnits, 200000000);
    });
  });

  console.log('\nnear-term money is never modelled as invested');

  ok('a goal inside a year is forced onto cash whatever the scenario', () => {
    // A projection that grows next year's school fees at 12% is reassuring
    // rather than useful, and the market can be down on the day.
    const result = across({ monthsRemaining: 6, assetClass: ASSET_CLASS.EQUITY });

    result.projections.forEach((projection) => {
      assert.strictEqual(projection.assumptionsUsed.assetClass, ASSET_CLASS.CASH);
      assert.strictEqual(projection.assumptionsUsed.substitutedForNearTerm, true);
    });
  });

  ok('the substitution is explained rather than silent', () => {
    const result = across({ monthsRemaining: 6 });

    assert.ok(result.caveats.some((caveat) => caveat.includes('modelled as cash')));
    assert.ok(
      result.projections[0].assumptionsUsed.substitutionReason.includes('down on the day')
    );
  });

  ok('the boundary is exactly twelve months', () => {
    const justInside = resolveAssetClass({
      requestedAssetClass: ASSET_CLASS.EQUITY, monthsRemaining: NEAR_TERM_MONTHS - 1
    });
    const atBoundary = resolveAssetClass({
      requestedAssetClass: ASSET_CLASS.EQUITY, monthsRemaining: NEAR_TERM_MONTHS
    });

    assert.strictEqual(justInside.substituted, true);
    assert.strictEqual(atBoundary.substituted, false);
  });

  ok('a goal already held in cash is not reported as substituted', () => {
    const resolved = resolveAssetClass({
      requestedAssetClass: ASSET_CLASS.CASH, monthsRemaining: 6
    });

    assert.strictEqual(resolved.substituted, false);
    assert.strictEqual(resolved.reason, null);
  });

  ok('a long goal keeps its own asset class', () => {
    const result = across({ monthsRemaining: 240, assetClass: ASSET_CLASS.EQUITY });

    assert.strictEqual(result.projections[0].assumptionsUsed.assetClass, ASSET_CLASS.EQUITY);
    assert.strictEqual(result.projections[0].assumptionsUsed.substitutedForNearTerm, false);
  });

  ok('horizon bands split at twelve months and seven years', () => {
    assert.strictEqual(horizonBand(6), HORIZON.SHORT);
    assert.strictEqual(horizonBand(36), HORIZON.MEDIUM);
    assert.strictEqual(horizonBand(240), HORIZON.LONG);
  });

  console.log('\nrefusing rather than guessing');

  ok('no approved assumption means no projection', () => {
    // A figure with no stated assumption behind it is what this whole stage
    // exists to prevent.
    const result = projectGoal({
      goal: goal(),
      scenarioType: SCENARIO.BASE,
      assumptions: [],
      monthlyContribution: money.money(5000000, 'INR'),
      currency: 'INR'
    });

    assert.strictEqual(result.projectable, false);
    assert.ok(result.reason.includes('built on a guess'));
  });

  ok('a draft assumption is never used', () => {
    const drafts = ASSUMPTIONS.map((row) => ({ ...row, approval_status: 'DRAFT' }));

    assert.strictEqual(
      findAssumption(drafts, {
        scenarioType: SCENARIO.BASE, assetClass: ASSET_CLASS.CASH, horizonBand: HORIZON.ANY
      }),
      null
    );
  });

  ok('a horizon-specific assumption is preferred over the catch-all', () => {
    const found = findAssumption(ASSUMPTIONS, {
      scenarioType: SCENARIO.BASE, assetClass: ASSET_CLASS.EQUITY, horizonBand: HORIZON.MEDIUM
    });

    assert.strictEqual(found.id, 8);
  });

  console.log('\ncompounding');

  ok('contributions are credited at month end, not month start', () => {
    // Assuming they arrive at the start credits an extra month of growth to
    // every one — small each time and material over twenty years.
    const value = futureValueOfContributions({
      startingBalance: money.money(0, 'INR'),
      monthlyContribution: money.money(100000, 'INR'),
      annualRate: 0.12,
      months: 12,
      currency: 'INR'
    });

    // An ordinary annuity of 12 × ₹1,000 at 12% nominal is just over ₹12,600;
    // crediting at month start would push it past ₹12,700.
    assert.ok(value.minorUnits > 1260000 && value.minorUnits < 1272000,
      `unexpected annuity value ${value.minorUnits}`);
  });

  ok('a zero return still accumulates the contributions', () => {
    const value = futureValueOfContributions({
      startingBalance: money.money(50000, 'INR'),
      monthlyContribution: money.money(10000, 'INR'),
      annualRate: 0,
      months: 10,
      currency: 'INR'
    });

    assert.strictEqual(value.minorUnits, 150000);
  });

  ok('a goal whose date has arrived is not projected forward', () => {
    const value = futureValueOfContributions({
      startingBalance: money.money(500000, 'INR'),
      monthlyContribution: money.money(10000, 'INR'),
      annualRate: 0.10,
      months: 0,
      currency: 'INR'
    });

    assert.strictEqual(value.minorUnits, 500000);
  });

  console.log('\nhow it is described');

  ok('the output says it is not a forecast', () => {
    const result = across();

    assert.ok(result.caveats.some((caveat) => caveat.includes('not a forecast')));
    assert.ok(result.caveats.some((caveat) => caveat.includes('not guaranteed')));
  });

  ok('a goal reached in some scenarios but not all says so', () => {
    const result = across({ monthsRemaining: 36 }, money.money(4000000, 'INR'));
    const reaching = result.projections.filter((p) => p.reachesTarget).length;

    if (reaching > 0 && reaching < 3) {
      assert.ok(result.caveats.some((caveat) => caveat.includes('of 3 scenarios')));
    }
  });

  ok('no engine source claims to predict anything', () => {
    // A guard rather than a style note. "Projection" and "prediction" mean
    // different things, and only one of them is honest about a model nobody
    // has validated.
    const engineDir = path.join(__dirname, '..', 'src', 'engine');
    const offenders = [];

    for (const file of fs.readdirSync(engineDir)) {
      if (!file.endsWith('.js')) continue;
      const source = fs.readFileSync(path.join(engineDir, file), 'utf8');
      // Matches predict/predicts/predicted/prediction, not "unpredictable".
      const match = source.match(/\bpredict(s|ed|ion|ions)?\b/i);
      if (match) offenders.push(`${file}: ${match[0]}`);
    }

    assert.deepStrictEqual(offenders, [],
      `engine source must not claim prediction: ${offenders.join(', ')}`);
  });

  console.log('\nall scenario engine tests passed');
})();
