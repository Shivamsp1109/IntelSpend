/**
 * Whether the goals a household has set are actually reachable.
 *
 * Two things here are easy to get wrong in ways that produce a confident number.
 *
 * **Inflation is applied only where the user said to.** "₹20,00,000 for a car in
 * three years" is ambiguous: it may be today's price, which has to be grown to
 * what the car will cost in 2029, or the figure they already worked out for
 * 2029, which must not be grown again. Inflating the second overstates the
 * target by years of compounding and would tell someone a reachable goal is out
 * of reach. There is no safe default, so `amountBasis` decides, and a target
 * marked as already-future or manually fixed is left exactly as entered.
 *
 * **The contribution the user committed to is never overwritten.** The engine
 * works out what a goal *would* need and returns it beside their figure; it does
 * not replace it. A suggestion is a suggestion, and quietly promoting one to a
 * commitment is how an app starts telling somebody what they decided.
 *
 * Feasibility is scenario-based rather than probabilistic. This version does not
 * claim a likelihood — "78% chance of reaching this" implies a validated model
 * nobody here has built. It says whether the goal fits, whether it would fit
 * with an adjustment, or that there is not enough information to say.
 */
const money = require('./money');
const { provenance, SOURCE_TYPE } = require('./provenance');
const { DEFAULT_INFLATION_RATE } = require('./constants');

const GOAL_STATUS = Object.freeze({
  ACTIVE: 'ACTIVE',
  PAUSED: 'PAUSED',
  ABANDONED: 'ABANDONED',
  ACHIEVED: 'ACHIEVED'
});

const FEASIBILITY = Object.freeze({
  FEASIBLE: 'FEASIBLE',
  FEASIBLE_WITH_ADJUSTMENT: 'FEASIBLE_WITH_ADJUSTMENT',
  NOT_FEASIBLE: 'NOT_FEASIBLE',
  INSUFFICIENT_DATA: 'INSUFFICIENT_DATA',
  ACHIEVED: 'ACHIEVED'
});

const AMOUNT_BASIS = Object.freeze({
  TODAYS_MONEY: 'TODAYS_MONEY',
  NOMINAL_FUTURE: 'NOMINAL_FUTURE',
  MANUALLY_FIXED: 'MANUALLY_FIXED'
});

const PRIORITY = Object.freeze({
  ESSENTIAL: 'ESSENTIAL',
  IMPORTANT: 'IMPORTANT',
  NICE_TO_HAVE: 'NICE_TO_HAVE'
});

const FLEXIBILITY = Object.freeze({
  DATE_FLEXIBLE: 'DATE_FLEXIBLE',
  AMOUNT_FLEXIBLE: 'AMOUNT_FLEXIBLE',
  BOTH_FLEXIBLE: 'BOTH_FLEXIBLE',
  FIXED: 'FIXED'
});

/** Ranking order when goals compete for the same surplus. */
const PRIORITY_RANK = Object.freeze({
  [PRIORITY.ESSENTIAL]: 0,
  [PRIORITY.IMPORTANT]: 1,
  [PRIORITY.NICE_TO_HAVE]: 2
});

const MILLIS_PER_MONTH = 30.436875 * 86_400_000;

/**
 * What the goal will actually cost on the day it falls due.
 *
 * The gate this engine exists for. Only `TODAYS_MONEY` is grown; the other two
 * bases mean the user has already accounted for the future, and touching them
 * would inflate a figure twice.
 */
function targetAtMaturity({ targetAmount, amountBasis, monthsRemaining, inflationRate }) {
  if (amountBasis !== AMOUNT_BASIS.TODAYS_MONEY) {
    return {
      amount: targetAmount,
      inflationApplied: false,
      note: amountBasis === AMOUNT_BASIS.MANUALLY_FIXED
        ? 'Used exactly as entered — you fixed this figure.'
        : 'Used as entered — you said this is what it will cost then.'
    };
  }

  if (monthsRemaining <= 0) {
    return { amount: targetAmount, inflationApplied: false, note: 'The date has arrived.' };
  }

  const years = monthsRemaining / 12;
  const grown = money.scaleBy(targetAmount, (1 + inflationRate) ** years);

  return {
    amount: grown,
    inflationApplied: true,
    note:
      `Grown from today's price at ${(inflationRate * 100).toFixed(1)}% a year, ` +
      'which is an assumption rather than a forecast.'
  };
}

/**
 * The monthly contribution that would meet the goal.
 *
 * No investment return is assumed. Returning money at an assumed rate would make
 * every goal look cheaper than it is, and the return is exactly the figure this
 * product has no standing to promise — so the requirement is stated in plain
 * saved rupees, and Stage 5's scenarios can show what a return would change.
 */
function requiredMonthlyContribution({ target, alreadySaved, monthsRemaining }) {
  const shortfall = money.coerceAtLeastZero(money.subtract(target, alreadySaved));
  if (money.isZero(shortfall)) return money.zero(target.currency);

  // A goal due this month or already past needs the whole shortfall now. Dividing
  // by a fraction of a month would report a monthly figure larger than the goal.
  const months = Math.max(1, monthsRemaining);
  return money.scaleBy(shortfall, 1 / months);
}

function monthsBetween(now, targetDate) {
  return Math.floor((targetDate - now) / MILLIS_PER_MONTH);
}

/**
 * Assesses one goal against what is actually available each month.
 *
 * `availableMonthly` is the surplus left after obligations, from the cash-flow
 * engine — not gross income, and not the raw surplus before commitments.
 */
function assessGoal({ goal, availableMonthly, currency, now, inflationRate }) {
  if (goal.currency !== currency) {
    throw new Error(
      `Goal '${goal.type}' is in ${goal.currency} but the assessment is in ` +
      `${currency}; this product has no exchange rate source.`
    );
  }

  const targetAmount = money.fromLegacyDouble(Number(goal.target_amount), currency);
  const alreadySaved = money.fromLegacyDouble(Number(goal.current_saved), currency);
  const committed = money.fromLegacyDouble(Number(goal.monthly_contribution), currency);
  const monthsRemaining = monthsBetween(now, Number(goal.target_date));

  const maturity = targetAtMaturity({
    targetAmount,
    amountBasis: goal.amount_basis ?? AMOUNT_BASIS.TODAYS_MONEY,
    monthsRemaining,
    inflationRate
  });

  const shortfall = money.coerceAtLeastZero(money.subtract(maturity.amount, alreadySaved));
  const required = requiredMonthlyContribution({
    target: maturity.amount,
    alreadySaved,
    monthsRemaining
  });

  const base = {
    localId: goal.local_id,
    type: goal.type,
    priority: goal.priority ?? PRIORITY.IMPORTANT,
    flexibility: goal.flexibility ?? FLEXIBILITY.BOTH_FLEXIBLE,
    status: goal.status ?? GOAL_STATUS.ACTIVE,
    currency,
    targetAsEntered: targetAmount,
    amountBasis: goal.amount_basis ?? AMOUNT_BASIS.TODAYS_MONEY,
    targetAtMaturity: maturity.amount,
    inflationApplied: maturity.inflationApplied,
    basisNote: maturity.note,
    alreadySaved,
    shortfall,
    monthsRemaining,
    // Named apart, deliberately. The first is theirs; the second is the engine's
    // arithmetic, offered rather than applied.
    committedMonthlyContribution: committed,
    requiredMonthlyContribution: required,
    contributionGap: money.coerceAtLeastZero(money.subtract(required, committed))
  };

  return { ...base, ...verdictFor(base, availableMonthly, goal) };
}

/**
 * Whether the goal fits, could fit, or does not.
 *
 * A goal the user cannot currently afford is only NOT_FEASIBLE when nothing
 * about it can move. Where the date or the amount is flexible, the honest answer
 * is that an adjustment would make it work — which is a different and far more
 * useful thing to be told.
 */
function verdictFor(assessed, availableMonthly, goal) {
  if (assessed.status === GOAL_STATUS.ACHIEVED || money.isZero(assessed.shortfall)) {
    return { feasibility: FEASIBILITY.ACHIEVED, reasons: ['Already saved for in full.'] };
  }
  if (assessed.status !== GOAL_STATUS.ACTIVE) {
    return {
      feasibility: FEASIBILITY.INSUFFICIENT_DATA,
      reasons: [`This goal is ${assessed.status.toLowerCase()}, so it is not being assessed.`]
    };
  }
  if (availableMonthly === null) {
    return {
      feasibility: FEASIBILITY.INSUFFICIENT_DATA,
      reasons: [
        'There is no reliable monthly surplus to measure this against yet — ' +
        'that needs a few complete months of income and spending.'
      ]
    };
  }

  const fitsInSurplus = money.compare(assessed.requiredMonthlyContribution, availableMonthly) <= 0;
  if (fitsInSurplus) {
    const reasons = [];
    if (money.isPositive(assessed.contributionGap)) {
      // The goal is affordable; what they have set aside is simply not enough
      // for it. Worth saying plainly, and never fixed by editing their figure.
      reasons.push(
        'Affordable out of your surplus, but more than you have currently ' +
        'committed to putting aside each month.'
      );
    }
    return { feasibility: FEASIBILITY.FEASIBLE, reasons };
  }

  const flexibility = goal.flexibility ?? FLEXIBILITY.BOTH_FLEXIBLE;
  const canAdjust = flexibility !== FLEXIBILITY.FIXED;

  return {
    feasibility: canAdjust ? FEASIBILITY.FEASIBLE_WITH_ADJUSTMENT : FEASIBILITY.NOT_FEASIBLE,
    reasons: canAdjust
      ? adjustmentsFor(assessed, availableMonthly, flexibility)
      : [
        'This needs more each month than is left over, and you have marked ' +
        'both the amount and the date as fixed.'
      ]
  };
}

/**
 * What would have to change, stated concretely.
 *
 * Only options the user has said are open. Suggesting a later date for a school
 * fee, or a smaller amount for a fixed obligation, is advice that cannot be
 * taken, and offering it makes the rest look equally unconsidered.
 */
function adjustmentsFor(assessed, availableMonthly, flexibility) {
  const options = [];

  if (flexibility === FLEXIBILITY.DATE_FLEXIBLE || flexibility === FLEXIBILITY.BOTH_FLEXIBLE) {
    if (money.isPositive(availableMonthly)) {
      const months = Math.ceil(
        assessed.shortfall.minorUnits / availableMonthly.minorUnits
      );
      options.push(
        `At everything you have spare, this would take about ${months} month(s) ` +
        `rather than ${Math.max(1, assessed.monthsRemaining)}.`
      );
    }
  }

  if (flexibility === FLEXIBILITY.AMOUNT_FLEXIBLE || flexibility === FLEXIBILITY.BOTH_FLEXIBLE) {
    const reachable = money.add(
      assessed.alreadySaved,
      money.scaleBy(availableMonthly, Math.max(1, assessed.monthsRemaining))
    );
    options.push(
      `Keeping the date, what is spare would reach about ` +
      `${money.toDecimalString(reachable)} of it.`
    );
  }

  if (options.length === 0) {
    options.push('More than is left over each month, once your obligations are met.');
  }
  return options;
}

/**
 * All goals together.
 *
 * The collective question is asked separately because it cannot be answered one
 * goal at a time: three goals can each need a comfortable amount and be
 * impossible in combination, and nothing looking at them individually sees that.
 */
function assessGoals({ goalRows, availableMonthly, currency, now, inflationRate = DEFAULT_INFLATION_RATE }) {
  const assessed = goalRows
    .map((goal) => assessGoal({ goal, availableMonthly, currency, now, inflationRate }))
    .sort((a, b) => {
      const byPriority = PRIORITY_RANK[a.priority] - PRIORITY_RANK[b.priority];
      return byPriority !== 0 ? byPriority : a.monthsRemaining - b.monthsRemaining;
    });

  const stillFunding = assessed.filter(
    (goal) => goal.status === GOAL_STATUS.ACTIVE && goal.feasibility !== FEASIBILITY.ACHIEVED
  );

  const totalRequired = money.sum(
    stillFunding.map((goal) => goal.requiredMonthlyContribution),
    currency
  );
  const totalCommitted = money.sum(
    stillFunding.map((goal) => goal.committedMonthlyContribution),
    currency
  );

  const collectivelyFeasible = availableMonthly === null
    ? null
    : money.compare(totalRequired, availableMonthly) <= 0;

  return {
    currency,
    goals: assessed,
    totalRequiredMonthly: totalRequired,
    totalCommittedMonthly: totalCommitted,
    collectivelyFeasible,
    inflationAssumption: {
      rate: inflationRate,
      // Labelled from the outset so nothing built on it can later be mistaken
      // for an observation.
      kind: 'ASSUMPTION',
      note: 'A planning assumption, not a forecast. It will be wrong in any given year.'
    },
    provenance: provenance({
      sourceType: SOURCE_TYPE.CONFIRMED,
      sourceRecordIds: assessed.map((goal) => goal.localId),
      asOf: now
    }),
    caveats: caveatsFor(assessed, collectivelyFeasible, availableMonthly)
  };
}

function caveatsFor(assessed, collectivelyFeasible, availableMonthly) {
  const caveats = [];

  if (assessed.length === 0) {
    caveats.push('No goals are recorded yet.');
    return caveats;
  }

  if (availableMonthly === null) {
    caveats.push(
      'Without a settled monthly surplus, these goals cannot be judged against ' +
      'what you can actually spare.'
    );
  }

  if (collectivelyFeasible === false &&
      assessed.every((goal) => goal.feasibility !== FEASIBILITY.NOT_FEASIBLE)) {
    caveats.push(
      'Each goal looks manageable on its own, but not all of them together out ' +
      'of what is left over each month.'
    );
  }

  const inflated = assessed.filter((goal) => goal.inflationApplied);
  if (inflated.length > 0) {
    caveats.push(
      `${inflated.length} goal(s) were entered in today's money and have been ` +
      'grown to what they would cost on the date you set. That growth is an ' +
      'assumption, not a forecast.'
    );
  }

  const underCommitted = assessed.filter(
    (goal) => goal.feasibility !== FEASIBILITY.ACHIEVED && money.isPositive(goal.contributionGap)
  );
  if (underCommitted.length > 0) {
    caveats.push(
      `${underCommitted.length} goal(s) need more each month than you have set ` +
      'aside for them. Your own figure is left as you set it.'
    );
  }

  return caveats;
}

module.exports = {
  assessGoals,
  assessGoal,
  targetAtMaturity,
  requiredMonthlyContribution,
  verdictFor,
  monthsBetween,
  GOAL_STATUS,
  FEASIBILITY,
  AMOUNT_BASIS,
  PRIORITY,
  FLEXIBILITY,
  PRIORITY_RANK
};
