/**
 * Projections under stated assumptions — three of them, never one.
 *
 * A single projected figure is the most persuasive and least honest thing this
 * engine could produce. "Your goal is reachable" carries the same weight on
 * screen whether it assumed 6% a year or 12%, and the reader has no way to tell
 * which. Showing three outcomes from the same facts makes the spread visible,
 * which is the actual finding: not what will happen, but how much the answer
 * depends on something nobody knows.
 *
 * Two rules hold everywhere in here.
 *
 * **Facts never move between scenarios.** What was earned, spent and saved is
 * observed; only what is projected from it varies. If the three runs disagree
 * about anything other than a projection, that is a bug, and there is a test
 * asserting it byte for byte.
 *
 * **Near-term money is never modelled as invested.** A goal eleven months away
 * gets a cash assumption whatever scenario is selected, because equity over one
 * year is not a return, it is a coin toss — and a projection that quietly grows
 * next year's school fees at 12% is how somebody ends up short at exactly the
 * wrong moment. The substitution is forced and the reason is stated.
 *
 * Nothing here is a forecast. A scenario says only: under these assumptions,
 * this follows. There is a test asserting that no engine source claims
 * otherwise — deliberately blunt, matching the word anywhere in the file rather
 * than trying to tell prose from output, because a guard nobody can accidentally
 * satisfy is worth more than a precise one.
 */
const crypto = require('crypto');
const money = require('./money');
const { ENGINE_VERSION } = require('./constants');

const SCENARIO = Object.freeze({
  CONSERVATIVE: 'CONSERVATIVE',
  BASE: 'BASE',
  OPTIMISTIC: 'OPTIMISTIC'
});

/** Order they are presented in: worst case first, so it is not the afterthought. */
const SCENARIO_ORDER = Object.freeze([
  SCENARIO.CONSERVATIVE, SCENARIO.BASE, SCENARIO.OPTIMISTIC
]);

const ASSET_CLASS = Object.freeze({
  CASH: 'CASH',
  DEBT: 'DEBT',
  EQUITY: 'EQUITY',
  GOLD: 'GOLD',
  REAL_ESTATE: 'REAL_ESTATE',
  BLENDED: 'BLENDED',
  NONE: 'NONE'
});

const HORIZON = Object.freeze({
  SHORT: 'SHORT', MEDIUM: 'MEDIUM', LONG: 'LONG', ANY: 'ANY'
});

/**
 * Below this, money is treated as cash regardless of what it is invested in.
 *
 * Twelve months is not a market cycle. Anything needed inside one has to be
 * there in nominal terms on the day, and the only assumption that respects that
 * is one with no market exposure in it.
 */
const NEAR_TERM_MONTHS = 12;

const MEDIUM_TERM_MONTHS = 84;

/** Which horizon band a number of months falls in. */
function horizonBand(months) {
  if (months < NEAR_TERM_MONTHS) return HORIZON.SHORT;
  if (months <= MEDIUM_TERM_MONTHS) return HORIZON.MEDIUM;
  return HORIZON.LONG;
}

/**
 * Picks the assumption a projection should use.
 *
 * The near-term substitution happens here rather than at each caller, so no
 * future consumer can forget it. It returns the class it actually used and why,
 * because a figure quietly computed on different assumptions from the ones the
 * user selected would be worse than one that simply refused.
 */
function resolveAssetClass({ requestedAssetClass, monthsRemaining }) {
  const band = horizonBand(monthsRemaining);

  const invested = requestedAssetClass !== ASSET_CLASS.CASH &&
    requestedAssetClass !== ASSET_CLASS.NONE;

  if (band === HORIZON.SHORT && invested) {
    return {
      assetClass: ASSET_CLASS.CASH,
      horizonBand: HORIZON.ANY,
      substituted: true,
      reason:
        `This is needed within ${NEAR_TERM_MONTHS} months, so it is modelled as ` +
        'cash rather than as an investment. A market can be down on the day you ' +
        'need the money, and a projection that assumed otherwise would be ' +
        'reassuring rather than useful.'
    };
  }

  return {
    assetClass: requestedAssetClass,
    horizonBand: requestedAssetClass === ASSET_CLASS.NONE ? HORIZON.ANY : band,
    substituted: false,
    reason: null
  };
}

/**
 * Finds an approved assumption in a loaded set.
 *
 * Falls back from a horizon-specific row to an `ANY` row, and returns null
 * rather than a default when neither exists — a projection with no stated
 * assumption behind it is exactly what this whole stage exists to prevent, so
 * it is refused rather than filled in.
 */
function findAssumption(assumptions, { scenarioType, assetClass, horizonBand: band, jurisdiction = 'IN' }) {
  const approved = assumptions.filter(
    (row) => row.approval_status === 'APPROVED' &&
      row.scenario_type === scenarioType &&
      row.jurisdiction === jurisdiction &&
      row.asset_class === assetClass
  );

  return approved.find((row) => row.time_horizon_band === band) ??
    approved.find((row) => row.time_horizon_band === HORIZON.ANY) ??
    null;
}

const asRate = (value) => (value === null || value === undefined ? null : Number(value) / 100);

/**
 * Grows an amount at an annual rate over a number of months.
 *
 * Compounded annually rather than monthly: the assumptions are stated as annual
 * figures, and compounding them monthly would quietly produce a higher effective
 * rate than the one on display.
 */
function grow(amount, annualRate, months) {
  if (annualRate === null || months <= 0) return amount;
  return money.scaleBy(amount, (1 + annualRate) ** (months / 12));
}

/**
 * Projects one goal under one scenario.
 *
 * Returns both what the goal will cost and what saving towards it would reach,
 * because the gap between those two is the answer — and each carries the
 * assumption it used, so the reader can see why the three scenarios differ.
 */
function projectGoal({ goal, scenarioType, assumptions, monthlyContribution, currency }) {
  const monthsRemaining = Math.max(0, goal.monthsRemaining);

  const resolved = resolveAssetClass({
    requestedAssetClass: goal.assetClass ?? ASSET_CLASS.BLENDED,
    monthsRemaining
  });

  const returnAssumption = findAssumption(assumptions, {
    scenarioType,
    assetClass: resolved.assetClass,
    horizonBand: resolved.horizonBand
  });
  const inflationAssumption = findAssumption(assumptions, {
    scenarioType,
    assetClass: ASSET_CLASS.NONE,
    horizonBand: HORIZON.ANY
  });

  if (!returnAssumption || !inflationAssumption) {
    return {
      scenarioType,
      projectable: false,
      reason:
        'No approved assumptions are available for this scenario, so no ' +
        'projection is offered rather than one built on a guess.'
    };
  }

  const expectedReturn = asRate(returnAssumption.expected_return);
  const inflation = asRate(inflationAssumption.inflation_rate);

  // Only a target stated in today's money is grown — the same gate the goal
  // engine applies, repeated here so a scenario cannot bypass it.
  const targetAtMaturity = goal.inflateTarget
    ? grow(goal.targetAmount, inflation, monthsRemaining)
    : goal.targetAmount;

  const projectedSavings = futureValueOfContributions({
    startingBalance: goal.alreadySaved,
    monthlyContribution,
    annualRate: expectedReturn,
    months: monthsRemaining,
    currency
  });

  const shortfall = money.coerceAtLeastZero(
    money.subtract(targetAtMaturity, projectedSavings)
  );

  return {
    scenarioType,
    projectable: true,
    monthsRemaining,
    targetAtMaturity,
    projectedSavings,
    shortfall,
    reachesTarget: money.isZero(shortfall),
    assumptionsUsed: {
      assetClass: resolved.assetClass,
      horizonBand: resolved.horizonBand,
      expectedReturnPercent: returnAssumption.expected_return === null
        ? null
        : Number(returnAssumption.expected_return),
      inflationPercent: inflationAssumption.inflation_rate === null
        ? null
        : Number(inflationAssumption.inflation_rate),
      returnAssumptionId: returnAssumption.id ?? null,
      inflationAssumptionId: inflationAssumption.id ?? null,
      source: returnAssumption.source,
      // Present whenever the engine overrode what was asked for.
      substitutedForNearTerm: resolved.substituted,
      substitutionReason: resolved.reason
    }
  };
}

/**
 * What regular saving plus a starting balance is worth at the end.
 *
 * Contributions are treated as arriving at each month's end and growing from
 * there. Assuming they arrive at the start would credit an extra month of
 * growth to every one of them — small per contribution and material over twenty
 * years, and flattering in exactly the direction that matters.
 */
function futureValueOfContributions({ startingBalance, monthlyContribution, annualRate, months, currency }) {
  if (months <= 0) return startingBalance;

  const rate = annualRate ?? 0;
  const monthlyRate = rate === 0 ? 0 : (1 + rate) ** (1 / 12) - 1;

  const grownBalance = grow(startingBalance, annualRate, months);

  if (monthlyRate === 0) {
    return money.add(grownBalance, money.multiply(monthlyContribution, months));
  }

  // Ordinary annuity: ((1+r)^n − 1) / r.
  const factor = ((1 + monthlyRate) ** months - 1) / monthlyRate;
  return money.add(grownBalance, money.scaleBy(monthlyContribution, factor));
}

/**
 * All three scenarios for one goal.
 *
 * The observed part — target as entered, what is already saved, what the user
 * contributes — is computed once and shared, so it is structurally impossible
 * for the three runs to disagree about a fact.
 */
function projectGoalAcrossScenarios({ goal, assumptions, monthlyContribution, currency, now }) {
  const observed = {
    localId: goal.localId,
    type: goal.type,
    targetAsEntered: goal.targetAmount,
    alreadySaved: goal.alreadySaved,
    committedMonthlyContribution: monthlyContribution,
    monthsRemaining: Math.max(0, goal.monthsRemaining),
    amountBasis: goal.amountBasis
  };

  const projections = SCENARIO_ORDER.map((scenarioType) =>
    projectGoal({ goal, scenarioType, assumptions, monthlyContribution, currency })
  );

  return {
    subjectType: 'GOAL',
    subjectLocalId: goal.localId,
    currency,
    observed,
    projections,
    inputsHash: hashInputs({ observed, currency }),
    engineVersion: ENGINE_VERSION,
    runAt: now,
    caveats: caveatsFor(projections)
  };
}

/**
 * A stable fingerprint of what went in.
 *
 * Lets a stored run be checked against a fresh one: same inputs and same
 * assumptions must give the same outputs, which turns reproducibility from a
 * claim into something a test can assert.
 */
function hashInputs(inputs) {
  const canonical = (value) => {
    if (value === null || typeof value !== 'object') return value;
    if (Array.isArray(value)) return value.map(canonical);
    return Object.keys(value).sort().reduce((ordered, key) => {
      ordered[key] = canonical(value[key]);
      return ordered;
    }, {});
  };

  return crypto.createHash('sha256')
    .update(JSON.stringify(canonical(inputs)))
    .digest('hex');
}

function caveatsFor(projections) {
  const caveats = [];
  const usable = projections.filter((projection) => projection.projectable);

  if (usable.length === 0) {
    caveats.push('No approved assumptions were available, so nothing was projected.');
    return caveats;
  }

  caveats.push(
    'These are three outcomes under three sets of assumptions, not a forecast. ' +
    'The spread between them is the point: it shows how much the answer depends ' +
    'on things nobody can know.'
  );

  if (usable.some((projection) => projection.assumptionsUsed.substitutedForNearTerm)) {
    caveats.push(
      usable.find((p) => p.assumptionsUsed.substitutedForNearTerm)
        .assumptionsUsed.substitutionReason
    );
  }

  const reaching = usable.filter((projection) => projection.reachesTarget).length;
  if (reaching > 0 && reaching < usable.length) {
    caveats.push(
      `This goal is reached under ${reaching} of ${usable.length} scenarios. ` +
      'Whether it works depends on returns that are not guaranteed.'
    );
  }

  caveats.push('Investment returns are not guaranteed and past returns do not indicate future ones.');

  return caveats;
}

module.exports = {
  projectGoalAcrossScenarios,
  projectGoal,
  resolveAssetClass,
  findAssumption,
  futureValueOfContributions,
  horizonBand,
  grow,
  hashInputs,
  SCENARIO,
  SCENARIO_ORDER,
  ASSET_CLASS,
  HORIZON,
  NEAR_TERM_MONTHS,
  MEDIUM_TERM_MONTHS
};
