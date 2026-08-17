/**
 * Whether there is enough data to answer a particular question.
 *
 * Per component, deliberately, rather than one switch over the whole
 * assessment. Refusing everything because income is missing would withhold net
 * worth, goal progress and an insurance inventory, none of which need income to
 * be correct — and a user who has not yet entered a salary would see an app
 * that appears to know nothing about them at all.
 *
 * So each component answers for itself, and a question is only blocked when the
 * thing it actually depends on is missing.
 */
const READINESS = Object.freeze({
  /** Enough data; the figure stands on its own. */
  READY: 'READY',
  /** Answerable, but on partial or stale input. Say so alongside the figure. */
  DEGRADED: 'DEGRADED',
  /** Cannot be answered. Never substitute a zero or a guess. */
  BLOCKED: 'BLOCKED'
});

/**
 * The components an assessment reports on, and what each genuinely needs.
 *
 * Written down here rather than decided inside each engine so that "missing
 * income blocks affordability but not net worth" is one reviewable table
 * instead of a rule rediscovered in six places.
 */
const COMPONENT = Object.freeze({
  CASH_FLOW: 'cashFlow',
  DEBT_SERVICE: 'debtService',
  AFFORDABILITY: 'affordability',
  NET_WORTH: 'netWorth',
  GOAL_PROGRESS: 'goalProgress',
  INSURANCE_GAP: 'insuranceGap',
  PORTFOLIO_ALIGNMENT: 'portfolioAlignment'
});

/**
 * What each component cannot be computed without.
 *
 * Goal *progress* needs only goals and what has been saved towards them — it is
 * a question about a target and a balance. Whether a goal is *affordable* is a
 * different question, needs income, and lives under AFFORDABILITY.
 */
const REQUIREMENTS = Object.freeze({
  [COMPONENT.CASH_FLOW]: ['income', 'expenses'],
  [COMPONENT.DEBT_SERVICE]: ['income', 'liabilities'],
  [COMPONENT.AFFORDABILITY]: ['income', 'expenses', 'commitments'],
  [COMPONENT.NET_WORTH]: ['assets', 'liabilities'],
  [COMPONENT.GOAL_PROGRESS]: ['goals'],
  [COMPONENT.INSURANCE_GAP]: ['insurance'],
  [COMPONENT.PORTFOLIO_ALIGNMENT]: ['assets', 'riskProfile']
});

/**
 * How long each kind of data stays believable.
 *
 * Per type, because one universal threshold is wrong in both directions: a
 * savings goal set eight months ago is still exactly as true as the day it was
 * entered, while an account balance from eight months ago tells you almost
 * nothing about today.
 */
const MAX_AGE_DAYS = Object.freeze({
  income: 95,
  expenses: 95,
  commitments: 120,
  liabilities: 180,
  assets: 120,
  goals: 400,
  insurance: 400,
  riskProfile: 730
});

/**
 * Grades one component from what is present and how old it is.
 *
 * @param component  a COMPONENT value
 * @param present    { [domain]: { hasData: boolean, newestAsOf: epochMillis|null } }
 * @param now        epoch millis
 */
function assess(component, present, now) {
  const required = REQUIREMENTS[component];
  if (!required) throw new Error(`Unknown component '${component}'.`);

  const missing = [];
  const stale = [];

  for (const domain of required) {
    const state = present[domain];
    if (!state || !state.hasData) {
      missing.push(domain);
      continue;
    }
    const maxAge = MAX_AGE_DAYS[domain];
    if (state.newestAsOf && maxAge) {
      const ageDays = (now - state.newestAsOf) / 86_400_000;
      if (ageDays > maxAge) stale.push(domain);
    }
  }

  if (missing.length > 0) {
    return Object.freeze({ status: READINESS.BLOCKED, missing, stale });
  }
  if (stale.length > 0) {
    return Object.freeze({ status: READINESS.DEGRADED, missing, stale });
  }
  return Object.freeze({ status: READINESS.READY, missing, stale });
}

/** Grades every component at once — the map a snapshot carries. */
function assessAll(present, now) {
  const result = {};
  for (const component of Object.values(COMPONENT)) {
    result[component] = assess(component, present, now);
  }
  return Object.freeze(result);
}

module.exports = {
  READINESS,
  COMPONENT,
  REQUIREMENTS,
  MAX_AGE_DAYS,
  assess,
  assessAll
};
