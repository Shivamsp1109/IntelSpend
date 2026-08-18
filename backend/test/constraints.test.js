/**
 * The hard constraints and the recommendation gate.
 *
 * Run with: npm test
 *
 * A constraint is only worth having if it refuses the case it was written for,
 * and every one of these was written for a case where the arithmetic looks fine.
 * Investing an emergency reserve scores well on every measure except the one
 * that matters. Adding a savings plan for somebody already in deficit is exactly
 * what an optimiser produces. Recommending where to put money without knowing
 * what the person can afford to lose looks identical to recommending it when you
 * do know.
 *
 * The invariants at the end are the structural claims: a rejected candidate can
 * never be selected, an assumption change never moves an observed fact, and
 * money moved between a user's own accounts never changes their net worth.
 */
const assert = require('assert');
const money = require('../src/engine/money');
const { READINESS, COMPONENT } = require('../src/engine/readiness');
const { RISK_LEVEL } = require('../src/engine/riskProfile');
const { RESERVE_STATUS } = require('../src/engine/emergencyFundEngine');
const { DEBT_LOAD } = require('../src/engine/debtEngine');
const { readAsset, assessNetWorth, LIQUIDITY, OWNERSHIP } = require('../src/engine/netWorthEngine');
const {
  protectEmergencyReserve, noWorseningDeficit, requireRiskProfile,
  noCurrencyMixing, requireComponentReadiness, constraintVersions
} = require('../src/engine/constraints');
const { evaluateCandidate, gate } = require('../src/engine/constraintEngine');
const { recommend, generateCandidates, CANDIDATE_TYPE } = require('../src/engine/recommendationEngine');

function ok(name, fn) {
  fn();
  console.log(`  ok  ${name}`);
}

const NOW = Date.UTC(2026, 7, 17);
const inr = (minor) => money.money(minor, 'INR');

/** A healthy position: real surplus, adequate reserve, everything ready. */
const healthyContext = (overrides = {}) => ({
  currency: 'INR',
  dataQuality: {
    readiness: Object.fromEntries(
      Object.values(COMPONENT).map((component) => [
        component, { status: READINESS.READY, missing: [], stale: [] }
      ])
    )
  },
  cashFlow: {
    monthsObserved: 6,
    obligations: { uncommittedSurplus: inr(4000000) }
  },
  emergencyFund: {
    currency: 'INR',
    eligibleReserve: inr(30000000),
    essentialMonthlySpend: inr(3000000),
    coverageMonths: 10,
    shortfall: inr(0),
    status: RESERVE_STATUS.ADEQUATE,
    target: { months: 4 }
  },
  debt: { debtLoad: DEBT_LOAD.LOW, debtServiceRatio: 0.1 },
  goals: { goals: [] },
  protection: { life: { status: 'ADEQUATE' } },
  riskProfile: {
    known: true, tolerance: RISK_LEVEL.MODERATE, capacity: RISK_LEVEL.MODERATE, isStale: false
  },
  ...overrides
});

const candidate = (overrides = {}) => ({
  id: 'test-candidate',
  type: CANDIDATE_TYPE.FUND_GOAL,
  title: 'Test',
  drawsFromReserve: false,
  addsMonthlyCommitment: false,
  isInvestmentRecommendation: false,
  dependsOn: [],
  ...overrides
});

(async () => {
  console.log('protecting the emergency reserve');

  ok('spending the reserve below its target is refused', () => {
    // The case the constraint exists for: a healthy reserve is precisely what
    // makes a big allocation look affordable.
    // ₹3,00,000 held against ₹30,000 of essentials. Spending ₹1,90,000 leaves
    // 3.7 months: clear of the floor, short of the 4-month target.
    const context = healthyContext();
    const result = protectEmergencyReserve.evaluate(
      candidate({ drawsFromReserve: true, amount: inr(19000000) }), context
    );

    assert.strictEqual(result.passed, false);
    assert.strictEqual(result.reason, 'BELOW_TARGET');
  });

  ok('the three-month floor holds even when the target is lower', () => {
    // Someone deliberately running a thinner buffer is making a choice; falling
    // below three months of essentials is not that.
    const context = healthyContext({
      emergencyFund: {
        ...healthyContext().emergencyFund,
        target: { months: 2 }
      }
    });
    const result = protectEmergencyReserve.evaluate(
      candidate({ drawsFromReserve: true, amount: inr(25000000) }), context
    );

    assert.strictEqual(result.passed, false);
    assert.strictEqual(result.reason, 'BELOW_ABSOLUTE_FLOOR');
  });

  ok('an amount the reserve comfortably covers is allowed', () => {
    const result = protectEmergencyReserve.evaluate(
      candidate({ drawsFromReserve: true, amount: inr(5000000) }), healthyContext()
    );

    assert.strictEqual(result.passed, true);
    assert.ok(result.detail.includes('months of cover would remain'));
  });

  ok('an unknown reserve refuses rather than assuming it is fine', () => {
    const context = healthyContext({
      emergencyFund: { ...healthyContext().emergencyFund, coverageMonths: null }
    });
    const result = protectEmergencyReserve.evaluate(
      candidate({ drawsFromReserve: true, amount: inr(100000) }), context
    );

    assert.strictEqual(result.passed, false);
    assert.strictEqual(result.reason, 'RESERVE_UNKNOWN');
  });

  ok('a candidate that does not touch the reserve is not checked', () => {
    assert.strictEqual(protectEmergencyReserve.appliesTo(candidate()), false);
  });

  console.log('\nnot worsening a deficit');

  ok('adding a commitment while already underwater is refused', () => {
    // A savings plan layered on a shortfall accelerates the problem.
    const context = healthyContext({
      cashFlow: { monthsObserved: 6, obligations: { uncommittedSurplus: inr(-500000) } }
    });
    const result = noWorseningDeficit.evaluate(
      candidate({ addsMonthlyCommitment: true, monthlyAmount: inr(100000) }), context
    );

    assert.strictEqual(result.passed, false);
    assert.strictEqual(result.reason, 'ALREADY_IN_DEFICIT');
  });

  ok('a commitment larger than the surplus is refused', () => {
    const result = noWorseningDeficit.evaluate(
      candidate({ addsMonthlyCommitment: true, monthlyAmount: inr(5000000) }), healthyContext()
    );

    assert.strictEqual(result.passed, false);
    assert.strictEqual(result.reason, 'WOULD_CREATE_DEFICIT');
  });

  ok('a commitment inside the surplus is allowed', () => {
    const result = noWorseningDeficit.evaluate(
      candidate({ addsMonthlyCommitment: true, monthlyAmount: inr(1000000) }), healthyContext()
    );

    assert.strictEqual(result.passed, true);
  });

  ok('no baseline means no judgement, not a pass', () => {
    const context = healthyContext({ cashFlow: { monthsObserved: 0, obligations: {} } });
    const result = noWorseningDeficit.evaluate(
      candidate({ addsMonthlyCommitment: true, monthlyAmount: inr(100000) }), context
    );

    assert.strictEqual(result.passed, false);
    assert.strictEqual(result.reason, 'NO_BASELINE');
  });

  ok('paying down a loan is not treated as adding a commitment', () => {
    // Blocking that on the grounds of a deficit would refuse the one suggestion
    // that helps.
    assert.strictEqual(
      noWorseningDeficit.appliesTo(candidate({ addsMonthlyCommitment: false })),
      false
    );
  });

  console.log('\nrequiring a confirmed risk profile');

  ok('an investment suggestion without a profile is refused', () => {
    const context = healthyContext({ riskProfile: { known: false, reason: 'none completed' } });
    const result = requireRiskProfile.evaluate(
      candidate({ isInvestmentRecommendation: true }), context
    );

    assert.strictEqual(result.passed, false);
    assert.strictEqual(result.reason, 'NO_CONFIRMED_PROFILE');
  });

  ok('a stale profile is refused', () => {
    const context = healthyContext({
      riskProfile: { known: true, capacity: RISK_LEVEL.MODERATE, isStale: true }
    });
    const result = requireRiskProfile.evaluate(
      candidate({ isInvestmentRecommendation: true }), context
    );

    assert.strictEqual(result.passed, false);
    assert.strictEqual(result.reason, 'PROFILE_STALE');
  });

  ok('a profile with tolerance but no capacity is refused', () => {
    // Capacity is the one that binds; without it there is nothing to measure
    // suitability against.
    const context = healthyContext({
      riskProfile: { known: true, tolerance: RISK_LEVEL.HIGH, capacity: null, isStale: false }
    });
    const result = requireRiskProfile.evaluate(
      candidate({ isInvestmentRecommendation: true }), context
    );

    assert.strictEqual(result.passed, false);
    assert.strictEqual(result.reason, 'NO_CAPACITY_RECORDED');
  });

  ok('non-investment suggestions need no profile at all', () => {
    // Building a reserve or paying down a loan is arithmetic about the user's
    // own cash, not a view about markets.
    assert.strictEqual(
      requireRiskProfile.appliesTo(candidate({ isInvestmentRecommendation: false })),
      false
    );
  });

  console.log('\nnever mixing currencies');

  ok('a candidate in another currency is refused with its reason', () => {
    const result = noCurrencyMixing.evaluate(
      candidate({ amount: money.money(50000, 'USD') }), healthyContext()
    );

    assert.strictEqual(result.passed, false);
    assert.ok(result.detail.includes('USD'));
    assert.ok(result.detail.includes('no exchange rate source'));
  });

  ok('matching currencies pass', () => {
    const result = noCurrencyMixing.evaluate(
      candidate({ amount: inr(100000) }), healthyContext()
    );

    assert.strictEqual(result.passed, true);
  });

  console.log('\nrequiring component readiness');

  ok('a candidate resting on a blocked component is refused', () => {
    const context = healthyContext();
    context.dataQuality.readiness[COMPONENT.CASH_FLOW] = {
      status: READINESS.BLOCKED, missing: ['income'], stale: []
    };

    const result = requireComponentReadiness.evaluate(
      candidate({ dependsOn: [COMPONENT.CASH_FLOW] }), context
    );

    assert.strictEqual(result.passed, false);
    assert.ok(result.detail.includes('income'));
  });

  ok('a degraded component passes but carries its caveat', () => {
    // Stale data is worth less, not worthless.
    const context = healthyContext();
    context.dataQuality.readiness[COMPONENT.CASH_FLOW] = {
      status: READINESS.DEGRADED, missing: [], stale: ['expenses']
    };

    const result = requireComponentReadiness.evaluate(
      candidate({ dependsOn: [COMPONENT.CASH_FLOW] }), context
    );

    assert.strictEqual(result.passed, true);
    assert.ok(result.detail.includes('out of date'));
  });

  console.log('\nthe gate');

  ok('every constraint is evaluated, not just up to the first failure', () => {
    // "Rejected because the reserve is thin" reads very differently from
    // "rejected because the reserve is thin and there is no risk profile".
    const context = healthyContext({
      riskProfile: { known: false, reason: 'none' },
      emergencyFund: { ...healthyContext().emergencyFund, coverageMonths: null }
    });

    const result = evaluateCandidate(candidate({
      drawsFromReserve: true,
      isInvestmentRecommendation: true,
      amount: inr(100000)
    }), context);

    assert.strictEqual(result.passed, false);
    assert.strictEqual(result.failedIds.length, 2);
  });

  ok('a constraint that throws fails closed', () => {
    // A bug in one rule must not become a recommendation that skipped it.
    const exploding = {
      id: 'EXPLODES',
      version: '1.0.0',
      appliesTo: () => true,
      evaluate() { throw new Error('boom'); }
    };

    const result = evaluateCandidate(candidate(), healthyContext(), [exploding]);

    assert.strictEqual(result.passed, false);
    assert.ok(result.rejectionReasons[0].includes('could not be checked'));
  });

  ok('constraint versions are recorded for the trace', () => {
    const versions = constraintVersions();

    assert.ok(Object.keys(versions).length >= 5);
    assert.ok(versions.PROTECT_EMERGENCY_RESERVE);
  });

  console.log('\ninvariants');

  ok('a rejected candidate never becomes the selected one', () => {
    // The structural claim the whole gate rests on.
    const context = healthyContext({
      cashFlow: { monthsObserved: 6, obligations: { uncommittedSurplus: inr(-500000) } },
      emergencyFund: {
        ...healthyContext().emergencyFund,
        status: RESERVE_STATUS.CRITICAL,
        coverageMonths: 1,
        shortfall: inr(9000000)
      }
    });

    const result = recommend(context);
    const rejectedIds = new Set(result.rejected.map((entry) => entry.id));

    assert.ok(rejectedIds.size > 0, 'expected something to be rejected in this position');
    if (result.selected) {
      assert.ok(!rejectedIds.has(result.selected.id));
    }
    result.alternatives.forEach((alternative) => {
      assert.ok(!rejectedIds.has(alternative.id));
    });
  });

  ok('when nothing survives the gate, nothing is selected', () => {
    // The case the previous test cannot reach: as long as one candidate passes,
    // a fallback to a rejected one stays invisible. Here every candidate is
    // refused — the deficit fix rests on a blocked component, and the reserve
    // top-up would add a commitment to somebody already underwater — so the only
    // honest answer is none.
    const context = healthyContext({
      cashFlow: { monthsObserved: 6, obligations: { uncommittedSurplus: inr(-500000) } },
      emergencyFund: {
        ...healthyContext().emergencyFund,
        status: RESERVE_STATUS.CRITICAL,
        coverageMonths: 1,
        shortfall: inr(9000000)
      }
    });
    context.dataQuality.readiness[COMPONENT.CASH_FLOW] = {
      status: READINESS.BLOCKED, missing: ['income'], stale: []
    };

    const result = recommend(context);

    assert.ok(result.rejected.length > 0, 'expected candidates to be generated and refused');
    assert.strictEqual(result.selected, null);
    assert.deepStrictEqual(result.alternatives, []);
    assert.ok(result.caveats.some((caveat) => caveat.includes('Nothing is suggested')));
  });

  ok('a deficit is ranked ahead of everything else', () => {
    const context = healthyContext({
      cashFlow: { monthsObserved: 6, obligations: { uncommittedSurplus: inr(-500000) } }
    });

    const result = recommend(context);
    assert.strictEqual(result.selected.type, CANDIDATE_TYPE.ADDRESS_DEFICIT);
  });

  ok('a transfer between the user\'s own accounts leaves net worth unchanged', () => {
    // Now that assets exist this can finally be asserted. Moving ₹1,00,000 from
    // a savings account into a fixed deposit changes nothing about what is owned.
    const before = [
      readAsset(asset({ local_id: 1, current_value: '500000.00' }), 'INR'),
      readAsset(asset({ local_id: 2, current_value: '300000.00', asset_type: 'FIXED_DEPOSIT' }), 'INR')
    ];
    const after = [
      readAsset(asset({ local_id: 1, current_value: '400000.00' }), 'INR'),
      readAsset(asset({ local_id: 2, current_value: '400000.00', asset_type: 'FIXED_DEPOSIT' }), 'INR')
    ];

    const worthOf = (assets) => assessNetWorth({
      assetRows: assets.map(toRow), debtAssessment: null, currency: 'INR', now: NOW
    }).netWorth.minorUnits;

    assert.strictEqual(worthOf(before), worthOf(after));
  });

  ok('nothing is suggested when nothing needs attention', () => {
    const result = recommend(healthyContext());

    assert.strictEqual(result.selected, null);
    assert.ok(result.caveats.some((caveat) => caveat.includes('Nothing stands out')));
  });

  ok('the output says it is not personal financial advice', () => {
    const context = healthyContext({
      cashFlow: { monthsObserved: 6, obligations: { uncommittedSurplus: inr(-500000) } }
    });

    assert.ok(recommend(context).caveats.some((caveat) => caveat.includes('not personal financial')));
  });

  console.log('\nall constraint and recommendation tests passed');
})();

function asset(overrides) {
  return {
    local_id: 1,
    label: 'Savings',
    asset_type: 'BANK_ACCOUNT',
    current_value: '500000.00',
    currency: 'INR',
    valuation_date: NOW,
    liquidity_class: LIQUIDITY.LIQUID_CASH,
    lock_in_until: null,
    ownership: OWNERSHIP.SELF,
    verification_source: 'MANUAL',
    ...overrides
  };
}

/** Turns a read asset back into the row shape assessNetWorth expects. */
function toRow(read) {
  return {
    local_id: read.localId,
    label: read.label,
    asset_type: read.assetType,
    current_value: money.toDecimalString(read.value),
    currency: read.value.currency,
    valuation_date: read.valuationDate,
    liquidity_class: read.liquidityClass,
    lock_in_until: read.lockInUntil,
    ownership: read.ownership,
    verification_source: read.verificationSource
  };
}
