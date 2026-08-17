/**
 * How long the user could go on if the income stopped.
 *
 * Two decisions carry this engine, and both are places the obvious answer is
 * wrong.
 *
 * What counts as reserve. Only money that could actually be reached in the week
 * something goes wrong. A five-year tax-saving deposit and a provident fund are
 * real wealth and useless in an emergency, and counting them produces a
 * comforting figure that fails exactly when it is tested. Liquidity is read from
 * the holding's own class and its lock-in date, never inferred from what kind of
 * asset it is.
 *
 * What it is measured against. Essential spending, not total spending — the
 * question is how long someone could keep the lights on, not how long they could
 * maintain their current life unchanged. Measuring against everything would
 * demand a reserve most people will never reach and make the figure useless.
 *
 * The target is a policy, not a law. "Six months" is a rule of thumb that
 * assumes stable employment; someone freelancing needs more and someone salaried
 * with no dependants may reasonably hold less. The policy is versioned and
 * stated, so a user who disagrees can see what they are disagreeing with.
 */
const money = require('./money');
const { provenance, SOURCE_TYPE } = require('./provenance');
const { isEssentialCategory, ESSENTIAL_SPLIT_CAVEAT, OUTFLOW_NATURES, NATURE } = require('./constants');
const { LIQUIDITY, OWNERSHIP } = require('./netWorthEngine');
const { STABILITY } = require('./cashFlowEngine');

/** Bumped when the target below changes, so an old assessment stays readable. */
const POLICY_VERSION = 'reserve-policy-1.0.0';

const RESERVE_STATUS = Object.freeze({
  ADEQUATE: 'ADEQUATE',
  NEEDS_ATTENTION: 'NEEDS_ATTENTION',
  CRITICAL: 'CRITICAL',
  UNKNOWN: 'UNKNOWN'
});

/**
 * Months of cover the policy asks for, by how steady the income is.
 *
 * Steadier income needs less buffer because it is likelier to still be there
 * next month. These are judgements, not findings.
 */
const TARGET_BY_STABILITY = Object.freeze({
  [STABILITY.STABLE]: 4,
  [STABILITY.VARIABLE]: 6,
  [STABILITY.VOLATILE]: 9,
  [STABILITY.UNKNOWN]: 6
});

/** Extra months asked for when debt payments take a large share of income. */
const HEAVY_DEBT_ABOVE = 0.35;
const HEAVY_DEBT_EXTRA_MONTHS = 2;

const ADEQUATE_AT = 1.0;
const NEEDS_ATTENTION_AT = 0.5;

/** Which liquidity classes can be spent in an emergency at all. */
const ELIGIBLE_LIQUIDITY = Object.freeze([LIQUIDITY.LIQUID_CASH, LIQUIDITY.LIQUID_INVESTMENT]);

/**
 * Whether a holding could actually be spent now.
 *
 * Each rejection carries its reason so the screen can explain why a holding the
 * user thinks of as savings is not counted, rather than appearing to ignore it.
 */
function eligibility(asset, now) {
  if (asset.ownership === OWNERSHIP.FAMILY) {
    return { eligible: false, reason: 'Recorded as family-owned.' };
  }
  if (!ELIGIBLE_LIQUIDITY.includes(asset.liquidityClass)) {
    return { eligible: false, reason: reasonForClass(asset.liquidityClass) };
  }
  if (asset.lockInUntil && asset.lockInUntil > now) {
    return { eligible: false, reason: 'Locked in until a future date.' };
  }
  return { eligible: true, reason: null };
}

function reasonForClass(liquidityClass) {
  switch (liquidityClass) {
    case LIQUIDITY.RETIREMENT_LOCKED:
      return 'Retirement money, not reachable without penalty.';
    case LIQUIDITY.PHYSICAL:
      return 'Would have to be sold before it could be spent.';
    case LIQUIDITY.ILLIQUID_INVESTMENT:
      return 'Would take weeks or months to reach.';
    default:
      return 'Not reachable quickly enough to count as reserve.';
  }
}

/**
 * Essential spending per month, from the same complete months the cash-flow
 * baseline used.
 *
 * The median again, for the same reason: an annual premium landing in one month
 * should not raise what the user is told they need to hold every month.
 */
function essentialMonthlySpend(expenseRows, months, currency) {
  const perMonth = months.map((month) => {
    const inMonth = expenseRows.filter((row) => {
      const when = Number(row.expense_date);
      return when >= month.start && when <= month.end;
    });

    const essential = inMonth.filter(
      (row) => OUTFLOW_NATURES.includes(row.nature) && countsAsEssential(row)
    );

    return money.sum(
      essential.map((row) => money.fromLegacyDouble(Number(row.amount), currency)),
      currency
    );
  });

  const withData = perMonth.filter((total) => !money.isZero(total));
  if (withData.length === 0) return { amount: money.zero(currency), monthsObserved: 0 };

  const sorted = [...withData].sort((a, b) => a.minorUnits - b.minorUnits);
  const middle = Math.floor(sorted.length / 2);
  const median = sorted.length % 2 === 1
    ? sorted[middle]
    : money.scaleBy(money.add(sorted[middle - 1], sorted[middle]), 0.5);

  return { amount: median, monthsObserved: withData.length };
}

/**
 * Debt repayment counts as essential whatever its category.
 *
 * A missed EMI has consequences a missed restaurant meal does not, so a reserve
 * that does not cover it is not covering the thing that matters most.
 */
const countsAsEssential = (row) =>
  row.nature === NATURE.LOAN_REPAYMENT || isEssentialCategory(row.category);

/** How many months of cover the policy asks of this particular user. */
function targetMonths({ incomeStability, debtServiceRatio }) {
  const base = TARGET_BY_STABILITY[incomeStability] ?? TARGET_BY_STABILITY[STABILITY.UNKNOWN];
  const extra = debtServiceRatio !== null && debtServiceRatio > HEAVY_DEBT_ABOVE
    ? HEAVY_DEBT_EXTRA_MONTHS
    : 0;

  return {
    months: base + extra,
    policyVersion: POLICY_VERSION,
    basedOn: [
      `Income steadiness read as ${incomeStability}.`,
      ...(extra > 0
        ? [`Loan payments take a large share of income, so ${extra} extra months are asked for.`]
        : [])
    ]
  };
}

/**
 * The reserve assessment.
 *
 * @param assets            already read via netWorthEngine.readAsset
 * @param expenseRows       rows over the baseline window, scoped to `currency`
 * @param months            the complete months the baseline covers
 * @param incomeStability   band from the cash-flow engine
 * @param debtServiceRatio  from the debt engine, or null
 */
function assessEmergencyFund({
  assets, expenseRows, months, incomeStability, debtServiceRatio, currency, now
}) {
  const graded = assets.map((asset) => ({ asset, ...eligibility(asset, now) }));
  const eligible = graded.filter((entry) => entry.eligible);
  const excluded = graded.filter((entry) => !entry.eligible);

  const reserve = money.sum(eligible.map((entry) => entry.asset.value), currency);
  const essential = essentialMonthlySpend(expenseRows, months, currency);
  const target = targetMonths({ incomeStability, debtServiceRatio });

  // Null rather than a large number when there is no essential spending on
  // record: "infinite months of cover" is not a finding, it is a missing
  // denominator, and showing it as a result would be actively misleading.
  const coverageMonths = money.isZero(essential.amount)
    ? null
    : reserve.minorUnits / essential.amount.minorUnits;

  const requiredReserve = money.scaleBy(essential.amount, target.months);
  const shortfall = money.coerceAtLeastZero(money.subtract(requiredReserve, reserve));

  return {
    currency,
    eligibleReserve: reserve,
    essentialMonthlySpend: essential.amount,
    monthsOfEssentialSpendObserved: essential.monthsObserved,
    coverageMonths,
    target,
    requiredReserve,
    shortfall,
    status: statusFor(coverageMonths, target.months),

    countedHoldings: eligible.map((entry) => ({
      localId: entry.asset.localId,
      label: entry.asset.label,
      value: entry.asset.value,
      liquidityClass: entry.asset.liquidityClass
    })),

    // Named individually rather than summed into an "excluded" total, so a user
    // can see the specific holding they expected to count and why it did not.
    excludedHoldings: excluded.map((entry) => ({
      localId: entry.asset.localId,
      label: entry.asset.label,
      value: entry.asset.value,
      liquidityClass: entry.asset.liquidityClass,
      reason: entry.reason
    })),

    provenance: provenance({
      sourceType: SOURCE_TYPE.CONFIRMED,
      sourceRecordIds: eligible.map((entry) => entry.asset.localId),
      asOf: now
    }),

    caveats: caveatsFor({ eligible, excluded, essential, coverageMonths, target })
  };
}

function statusFor(coverageMonths, targetMonths) {
  if (coverageMonths === null) return RESERVE_STATUS.UNKNOWN;

  const share = coverageMonths / targetMonths;
  if (share >= ADEQUATE_AT) return RESERVE_STATUS.ADEQUATE;
  if (share >= NEEDS_ATTENTION_AT) return RESERVE_STATUS.NEEDS_ATTENTION;
  return RESERVE_STATUS.CRITICAL;
}

function caveatsFor({ eligible, excluded, essential, coverageMonths, target }) {
  const caveats = [];

  if (eligible.length === 0) {
    caveats.push(
      'No holdings are recorded that could be reached quickly, so there is no ' +
      'reserve to measure.'
    );
  }

  if (coverageMonths === null) {
    caveats.push(
      'No essential spending is recorded for these months, so there is nothing ' +
      'to measure the reserve against.'
    );
  } else {
    caveats.push(ESSENTIAL_SPLIT_CAVEAT);
  }

  if (essential.monthsObserved > 0 && essential.monthsObserved < 3) {
    caveats.push(
      `Essential spending is based on ${essential.monthsObserved} month(s), which ` +
      'is a starting point rather than a settled figure.'
    );
  }

  if (excluded.length > 0) {
    caveats.push(
      `${excluded.length} holding(s) are not counted as reserve because they ` +
      'could not be reached quickly. They are listed with the reason.'
    );
  }

  caveats.push(
    `A target of ${target.months} months is this app's guidance, not a rule. ` +
    'Your own circumstances may justify more or less.'
  );

  return caveats;
}

module.exports = {
  assessEmergencyFund,
  eligibility,
  essentialMonthlySpend,
  targetMonths,
  statusFor,
  countsAsEssential,
  RESERVE_STATUS,
  TARGET_BY_STABILITY,
  ELIGIBLE_LIQUIDITY,
  POLICY_VERSION,
  HEAVY_DEBT_ABOVE
};
