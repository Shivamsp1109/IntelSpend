/**
 * Per-component data sufficiency.
 *
 * Run with: npm test
 *
 * The case these exist for is a user who has recorded expenses and goals but no
 * income. One blocking switch would refuse the whole assessment and show them an
 * app that appears to know nothing about them, when in fact it can answer what
 * they have saved towards a goal perfectly well. Each test pins one question
 * that must stay answerable, or one that must genuinely refuse.
 */
const assert = require('assert');
const { READINESS, COMPONENT, assess, assessAll } = require('../src/engine/readiness');
const { provenance, SOURCE_TYPE, isOverwritable } = require('../src/engine/provenance');

function ok(name, fn) {
  fn();
  console.log(`  ok  ${name}`);
}

const NOW = Date.UTC(2026, 7, 17);
const daysAgo = (days) => NOW - days * 86_400_000;

const fresh = (hasData = true) => ({ hasData, newestAsOf: daysAgo(3) });
const absent = () => ({ hasData: false, newestAsOf: null });

(async () => {
  console.log('component readiness');

  ok('missing income blocks cash flow but never goal progress', () => {
    const present = {
      income: absent(),
      expenses: fresh(),
      commitments: fresh(),
      goals: fresh(),
      assets: fresh(),
      liabilities: fresh(),
      insurance: fresh(),
      riskProfile: fresh()
    };

    const all = assessAll(present, NOW);

    assert.strictEqual(all[COMPONENT.CASH_FLOW].status, READINESS.BLOCKED);
    assert.strictEqual(all[COMPONENT.DEBT_SERVICE].status, READINESS.BLOCKED);
    assert.strictEqual(all[COMPONENT.AFFORDABILITY].status, READINESS.BLOCKED);

    // The whole point: these do not need income and must still answer.
    assert.strictEqual(all[COMPONENT.GOAL_PROGRESS].status, READINESS.READY);
    assert.strictEqual(all[COMPONENT.NET_WORTH].status, READINESS.READY);
    assert.strictEqual(all[COMPONENT.INSURANCE_GAP].status, READINESS.READY);
    assert.strictEqual(all[COMPONENT.PORTFOLIO_ALIGNMENT].status, READINESS.READY);
  });

  ok('a blocked component names what is missing', () => {
    const result = assess(COMPONENT.CASH_FLOW, { income: absent(), expenses: fresh() }, NOW);

    assert.strictEqual(result.status, READINESS.BLOCKED);
    assert.deepStrictEqual(result.missing, ['income']);
  });

  ok('stale data degrades rather than blocks', () => {
    // Four-month-old expenses are worth less but are not nothing; the figure
    // stands with a caveat rather than being withheld.
    const result = assess(
      COMPONENT.CASH_FLOW,
      { income: fresh(), expenses: { hasData: true, newestAsOf: daysAgo(120) } },
      NOW
    );

    assert.strictEqual(result.status, READINESS.DEGRADED);
    assert.deepStrictEqual(result.stale, ['expenses']);
  });

  ok('freshness thresholds differ by data type', () => {
    // Identical age, opposite verdicts: a goal set eight months ago is as true
    // as the day it was entered; a balance from eight months ago is not.
    const eightMonths = { hasData: true, newestAsOf: daysAgo(240) };

    assert.strictEqual(
      assess(COMPONENT.GOAL_PROGRESS, { goals: eightMonths }, NOW).status,
      READINESS.READY
    );
    assert.strictEqual(
      assess(
        COMPONENT.NET_WORTH,
        { assets: eightMonths, liabilities: fresh() },
        NOW
      ).status,
      READINESS.DEGRADED
    );
  });

  ok('missing beats stale when both apply', () => {
    const result = assess(
      COMPONENT.NET_WORTH,
      { assets: { hasData: true, newestAsOf: daysAgo(300) }, liabilities: absent() },
      NOW
    );

    assert.strictEqual(result.status, READINESS.BLOCKED);
  });

  ok('an unknown component is refused rather than silently passing', () => {
    assert.throws(() => assess('vibes', {}, NOW), /Unknown component/);
  });

  console.log('\nprovenance');

  ok('a confirmed figure is never overwritable', () => {
    const confirmed = provenance({ sourceType: SOURCE_TYPE.CONFIRMED, asOf: NOW });
    const inferred = provenance({ sourceType: SOURCE_TYPE.INFERRED, asOf: NOW });

    assert.strictEqual(isOverwritable(confirmed), false);
    assert.strictEqual(isOverwritable(inferred), true);
  });

  ok('a calculated figure must name its formula', () => {
    assert.throws(
      () => provenance({ sourceType: SOURCE_TYPE.CALCULATED, asOf: NOW }),
      /must name the formula/
    );

    const traced = provenance({
      sourceType: SOURCE_TYPE.CALCULATED,
      asOf: NOW,
      formulaId: 'cashflow.netCashFlow.v1'
    });
    assert.strictEqual(traced.formulaId, 'cashflow.netCashFlow.v1');
  });

  ok('an unknown source type is refused', () => {
    assert.throws(() => provenance({ sourceType: 'VIBES' }), /Unknown provenance/);
  });

  console.log('\nall readiness and provenance tests passed');
})();
