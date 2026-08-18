/**
 * What the engine suggests, and everything it refused to.
 *
 * The order is the design: candidates are generated first, gated second, ranked
 * third. Generation is allowed to be optimistic — it proposes anything the data
 * makes conceivable — because the gate is what makes that safe. Filtering during
 * generation would hide the refusals, and a recommendation is only explicable
 * alongside what was considered and rejected.
 *
 * Nothing here calls a model. That is deliberate and is what makes the central
 * principle provable: the conclusions exist, tested and reproducible, before any
 * model is in a position to interfere with them. When one arrives it will
 * explain this output, not produce it.
 *
 * Ranking is a stated policy rather than a score. A weighted number would imply
 * a precision nobody established and would be impossible to argue with; an
 * ordered list of reasons can be read and disagreed with.
 */
const money = require('./money');
const { gate, constraintVersions } = require('./constraintEngine');
const { COMPONENT } = require('./readiness');
const { RESERVE_STATUS } = require('./emergencyFundEngine');
const { DEBT_LOAD } = require('./debtEngine');
const { FEASIBILITY } = require('./goalEngine');

/** Bumped when generation or ranking changes what comes out. */
const POLICY_VERSION = '1.0.0';

const CANDIDATE_TYPE = Object.freeze({
  BUILD_RESERVE: 'BUILD_RESERVE',
  REDUCE_DEBT: 'REDUCE_DEBT',
  FUND_GOAL: 'FUND_GOAL',
  CLOSE_PROTECTION_GAP: 'CLOSE_PROTECTION_GAP',
  ADDRESS_DEFICIT: 'ADDRESS_DEFICIT'
});

/**
 * What gets attended to first when several things are wrong.
 *
 * A stated ordering rather than an emergent one. Someone in monthly deficit with
 * no reserve and an expensive loan has three real problems, and the sequence
 * matters: a deficit compounds, a missing reserve turns the next setback into
 * borrowing, and only then does the cost of existing debt become the thing to
 * work on. Goals come last not because they matter least to the user but because
 * funding one out of a fragile position is how the fragility becomes permanent.
 */
const PRIORITY_ORDER = Object.freeze([
  CANDIDATE_TYPE.ADDRESS_DEFICIT,
  CANDIDATE_TYPE.BUILD_RESERVE,
  CANDIDATE_TYPE.CLOSE_PROTECTION_GAP,
  CANDIDATE_TYPE.REDUCE_DEBT,
  CANDIDATE_TYPE.FUND_GOAL
]);

/**
 * Proposes everything the data makes conceivable.
 *
 * Deliberately not filtered here. A candidate that the gate will reject is worth
 * generating, because the rejection is itself the useful output: "we did not
 * suggest investing because your reserve is thin" tells the user something,
 * where silence tells them nothing.
 */
function generateCandidates(context) {
  const { cashFlow, emergencyFund, debt, goals, protection, currency } = context;
  const candidates = [];

  const surplus = cashFlow?.obligations?.uncommittedSurplus ?? null;

  // A monthly shortfall outranks everything. Nothing else is worth doing while
  // the position gets worse every month by construction.
  if (surplus && money.isNegative(surplus)) {
    candidates.push({
      id: 'address-deficit',
      type: CANDIDATE_TYPE.ADDRESS_DEFICIT,
      title: 'Close the monthly shortfall',
      rationale:
        `Your outgoings exceed what comes in by about ` +
        `${money.toDecimalString(money.negate(surplus))} a month. Until that ` +
        'turns around, everything else builds on a position that is worsening.',
      amount: money.negate(surplus),
      // Reducing an outflow, so it neither draws on the reserve nor adds a
      // commitment — the two things the gate is looking for.
      drawsFromReserve: false,
      addsMonthlyCommitment: false,
      isInvestmentRecommendation: false,
      dependsOn: [COMPONENT.CASH_FLOW]
    });
  }

  if (emergencyFund && emergencyFund.status !== RESERVE_STATUS.ADEQUATE &&
      emergencyFund.status !== RESERVE_STATUS.UNKNOWN &&
      money.isPositive(emergencyFund.shortfall)) {
    candidates.push({
      id: 'build-reserve',
      type: CANDIDATE_TYPE.BUILD_RESERVE,
      title: 'Build up your emergency reserve',
      rationale:
        `You hold about ${(emergencyFund.coverageMonths ?? 0).toFixed(1)} months ` +
        `of essential spending against a suggested ${emergencyFund.target.months}. ` +
        `Closing that gap needs ${money.toDecimalString(emergencyFund.shortfall)}.`,
      amount: emergencyFund.shortfall,
      monthlyAmount: surplus && money.isPositive(surplus)
        ? money.scaleBy(surplus, 0.5)
        : null,
      drawsFromReserve: false,
      addsMonthlyCommitment: true,
      isInvestmentRecommendation: false,
      dependsOn: [COMPONENT.CASH_FLOW]
    });
  }

  if (protection && protection.life?.status === 'GAP_DETECTED' && protection.life.shortfall) {
    candidates.push({
      id: 'close-life-cover-gap',
      type: CANDIDATE_TYPE.CLOSE_PROTECTION_GAP,
      title: 'Look at your life cover',
      rationale:
        `Your recorded cover is about ` +
        `${money.toDecimalString(protection.life.shortfall)} short of the ` +
        'conventional guideline for your income. That guideline is a rule of ' +
        'thumb rather than a calculation of what your family needs.',
      drawsFromReserve: false,
      addsMonthlyCommitment: false,
      isInvestmentRecommendation: false,
      dependsOn: [COMPONENT.INSURANCE_GAP]
    });
  }

  if (debt && debt.debtLoad === DEBT_LOAD.HIGH && surplus && money.isPositive(surplus)) {
    candidates.push({
      id: 'reduce-debt',
      type: CANDIDATE_TYPE.REDUCE_DEBT,
      title: 'Put spare money towards your loans',
      rationale:
        `Loan payments take about ${Math.round((debt.debtServiceRatio ?? 0) * 100)}% ` +
        'of your income. Paying down the balance lowers what you owe each month ' +
        'as well as the total interest.',
      amount: surplus,
      // Draws on liquid money, so the reserve gate applies.
      drawsFromReserve: true,
      addsMonthlyCommitment: false,
      isInvestmentRecommendation: false,
      dependsOn: [COMPONENT.DEBT_SERVICE, COMPONENT.CASH_FLOW]
    });
  }

  for (const goal of goals?.goals ?? []) {
    if (goal.feasibility !== FEASIBILITY.FEASIBLE &&
        goal.feasibility !== FEASIBILITY.FEASIBLE_WITH_ADJUSTMENT) continue;
    if (!money.isPositive(goal.contributionGap)) continue;

    candidates.push({
      id: `fund-goal-${goal.localId}`,
      type: CANDIDATE_TYPE.FUND_GOAL,
      title: `Put more towards your ${String(goal.type).toLowerCase()} goal`,
      rationale:
        `This needs ${money.toDecimalString(goal.requiredMonthlyContribution)} a ` +
        `month and you have committed ` +
        `${money.toDecimalString(goal.committedMonthlyContribution)}.`,
      monthlyAmount: goal.contributionGap,
      subjectLocalId: goal.localId,
      drawsFromReserve: false,
      addsMonthlyCommitment: true,
      isInvestmentRecommendation: false,
      dependsOn: [COMPONENT.GOAL_PROGRESS, COMPONENT.AFFORDABILITY]
    });
  }

  return candidates.map((candidate) => ({ ...candidate, currency }));
}

/**
 * Orders what survived the gate.
 *
 * By stated priority, then by size within a type. No weighting, no score: the
 * reason one thing comes before another should be readable in a sentence.
 */
function rank(eligible) {
  return [...eligible].sort((a, b) => {
    const byType = PRIORITY_ORDER.indexOf(a.type) - PRIORITY_ORDER.indexOf(b.type);
    if (byType !== 0) return byType;

    const sizeOf = (candidate) =>
      candidate.amount?.minorUnits ?? candidate.monthlyAmount?.minorUnits ?? 0;
    return sizeOf(b) - sizeOf(a);
  });
}

/**
 * The full run: generate, gate, rank.
 *
 * Returns the rejected candidates alongside the selected one, because a trace
 * showing only the winner explains nothing — and because the reasons a
 * suggestion was withheld are often more useful to the user than the suggestion
 * that survived.
 */
function recommend(context) {
  const candidates = generateCandidates(context);
  const { eligible, rejected } = gate(candidates, context);
  const ranked = rank(eligible);

  return {
    policyVersion: POLICY_VERSION,
    constraintVersions: constraintVersions(),
    currency: context.currency,
    selected: ranked[0] ?? null,
    alternatives: ranked.slice(1),
    rejected,
    caveats: caveatsFor(ranked, rejected)
  };
}

function caveatsFor(ranked, rejected) {
  const caveats = [];

  if (ranked.length === 0 && rejected.length === 0) {
    caveats.push('Nothing stands out as needing attention from what is recorded.');
    return caveats;
  }

  if (ranked.length === 0) {
    caveats.push(
      'Nothing is suggested. Everything the figures pointed at was ruled out — ' +
      'the reasons are listed, and most of them are things you can change.'
    );
  }

  if (rejected.length > 0) {
    caveats.push(
      `${rejected.length} option(s) were considered and not suggested. What ruled ` +
      'each one out is recorded.'
    );
  }

  caveats.push(
    'These follow from what you have recorded. They are not personal financial ' +
    'advice, and nothing here recommends a specific investment or product.'
  );

  return caveats;
}

module.exports = {
  recommend,
  generateCandidates,
  rank,
  POLICY_VERSION,
  CANDIDATE_TYPE,
  PRIORITY_ORDER
};
