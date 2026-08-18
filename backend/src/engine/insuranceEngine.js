/**
 * Whether the household is covered, and where it is not.
 *
 * Insurance is the one part of a financial picture where **absence is the
 * finding**, and that shapes the whole design. Everywhere else, no data means no
 * answer; here, no life policy recorded is either a real and serious gap or
 * simply something the user has not entered — and those are opposite
 * conclusions.
 *
 * So the engine never reports a gap it cannot distinguish from missing data. It
 * returns UNKNOWN when nothing is recorded, and only reports GAP_DETECTED once
 * there is enough on file to be confident the absence is real. Telling somebody
 * they have no life cover when they simply have not typed it in would be alarming
 * and wrong; telling them everything is fine on the same evidence would be worse.
 *
 * Cover adequacy uses plain multiples of income and of essential spending. These
 * are conventions, stated as such, not actuarial findings — a real needs
 * analysis accounts for dependants' ages, existing assets, education costs and a
 * dozen other things this app does not know.
 */
const money = require('./money');
const { provenance, SOURCE_TYPE } = require('./provenance');

const PROTECTION_STATUS = Object.freeze({
  ADEQUATE: 'ADEQUATE',
  GAP_DETECTED: 'GAP_DETECTED',
  UNKNOWN: 'UNKNOWN'
});

const POLICY_TYPE = Object.freeze({
  TERM_LIFE: 'TERM_LIFE',
  WHOLE_LIFE: 'WHOLE_LIFE',
  ENDOWMENT: 'ENDOWMENT',
  ULIP: 'ULIP',
  HEALTH: 'HEALTH',
  CRITICAL_ILLNESS: 'CRITICAL_ILLNESS',
  PERSONAL_ACCIDENT: 'PERSONAL_ACCIDENT',
  MOTOR: 'MOTOR',
  HOME: 'HOME',
  TRAVEL: 'TRAVEL',
  OTHER: 'OTHER'
});

/** Policies that pay out on death, whatever else they also do. */
const LIFE_TYPES = Object.freeze([
  POLICY_TYPE.TERM_LIFE, POLICY_TYPE.WHOLE_LIFE, POLICY_TYPE.ENDOWMENT, POLICY_TYPE.ULIP
]);

const HEALTH_TYPES = Object.freeze([POLICY_TYPE.HEALTH, POLICY_TYPE.CRITICAL_ILLNESS]);

/**
 * Conventional cover multiples. Rules of thumb, and labelled as such.
 *
 * Ten times annual income for life cover is a common planning heuristic in
 * India; it is not a calculation of what a particular family needs.
 */
const LIFE_COVER_MULTIPLE = 10;
const HEALTH_COVER_MONTHS_OF_ESSENTIALS = 12;

const POLICY_VERSION = 'protection-policy-1.0.0';

function readPolicy(row, currency, now) {
  if (row.currency !== currency) {
    throw new Error(
      `Policy '${row.label}' is denominated in ${row.currency} but the assessment ` +
      `is in ${currency}; this product has no exchange rate source.`
    );
  }

  const endsAt = row.policy_end_date === null || row.policy_end_date === undefined
    ? null
    : Number(row.policy_end_date);

  return {
    localId: row.local_id,
    label: row.label,
    policyType: row.policy_type,
    provider: row.provider ?? null,
    sumAssured: money.fromDecimalString(row.sum_assured, currency),
    premiumAmount: row.premium_amount === null || row.premium_amount === undefined
      ? null
      : money.fromDecimalString(row.premium_amount, currency),
    premiumCadence: row.premium_cadence,
    policyEndDate: endsAt,
    // Lapsed cover is not cover. Counted separately rather than dropped, so the
    // screen can say a policy has expired instead of appearing to lose it.
    hasLapsed: endsAt !== null && endsAt < now,
    nomineeSet: row.nominee_set === null || row.nominee_set === undefined
      ? null
      : row.nominee_set === 1
  };
}

/**
 * Grades one kind of cover.
 *
 * The distinction between "no policies at all" and "policies, but not enough" is
 * load-bearing: the first is UNKNOWN because it cannot be told from an empty
 * record, and the second is a real finding.
 */
function gradeCover({ policies, required, anyPolicyRecorded }) {
  if (policies.length === 0) {
    return {
      status: anyPolicyRecorded ? PROTECTION_STATUS.GAP_DETECTED : PROTECTION_STATUS.UNKNOWN,
      held: null,
      required,
      shortfall: null,
      note: anyPolicyRecorded
        ? 'You have recorded other policies but none of this kind.'
        : 'Nothing recorded, so this cannot be assessed either way.'
    };
  }

  const held = money.sum(policies.map((policy) => policy.sumAssured), policies[0].sumAssured.currency);
  if (required === null) {
    return {
      status: PROTECTION_STATUS.UNKNOWN,
      held,
      required: null,
      shortfall: null,
      note: 'Not enough is known about your income or spending to say how much would be enough.'
    };
  }

  const shortfall = money.coerceAtLeastZero(money.subtract(required, held));

  return {
    status: money.isZero(shortfall) ? PROTECTION_STATUS.ADEQUATE : PROTECTION_STATUS.GAP_DETECTED,
    held,
    required,
    shortfall,
    note: null
  };
}

/**
 * The protection assessment.
 *
 * @param policyRows     rows from insurance_policies, scoped to `currency`
 * @param monthlyIncome  the cash-flow baseline, or null
 * @param essentialMonthlySpend  from the reserve engine, or null
 */
function assessProtection({ policyRows, monthlyIncome, essentialMonthlySpend, currency, now }) {
  const policies = policyRows.map((row) => readPolicy(row, currency, now));
  const live = policies.filter((policy) => !policy.hasLapsed);
  const lapsed = policies.filter((policy) => policy.hasLapsed);

  const anyPolicyRecorded = policies.length > 0;

  const lifeRequired = monthlyIncome && !money.isZero(monthlyIncome)
    ? money.scaleBy(monthlyIncome, 12 * LIFE_COVER_MULTIPLE)
    : null;
  const healthRequired = essentialMonthlySpend && !money.isZero(essentialMonthlySpend)
    ? money.scaleBy(essentialMonthlySpend, HEALTH_COVER_MONTHS_OF_ESSENTIALS)
    : null;

  const life = gradeCover({
    policies: live.filter((policy) => LIFE_TYPES.includes(policy.policyType)),
    required: lifeRequired,
    anyPolicyRecorded
  });
  const health = gradeCover({
    policies: live.filter((policy) => HEALTH_TYPES.includes(policy.policyType)),
    required: healthRequired,
    anyPolicyRecorded
  });

  return {
    currency,
    policyVersion: POLICY_VERSION,
    life,
    health,
    // The headline is the worst of the two, and UNKNOWN never reads as adequate.
    overall: worstOf([life.status, health.status]),
    policies: live.map(summarise),
    lapsedPolicies: lapsed.map(summarise),
    coverBasis: {
      lifeCoverMultiple: LIFE_COVER_MULTIPLE,
      healthCoverMonths: HEALTH_COVER_MONTHS_OF_ESSENTIALS,
      kind: 'CONVENTION',
      note:
        'Cover targets are common rules of thumb, not a calculation of what your ' +
        'family needs. A real needs analysis would account for dependants, their ' +
        'ages, and what you already own.'
    },
    provenance: provenance({
      sourceType: SOURCE_TYPE.CONFIRMED,
      sourceRecordIds: live.map((policy) => policy.localId),
      asOf: now
    }),
    caveats: caveatsFor({ policies, live, lapsed, life, health })
  };
}

const summarise = (policy) => ({
  localId: policy.localId,
  label: policy.label,
  policyType: policy.policyType,
  provider: policy.provider,
  sumAssured: policy.sumAssured,
  policyEndDate: policy.policyEndDate,
  nomineeSet: policy.nomineeSet
});

function worstOf(statuses) {
  if (statuses.includes(PROTECTION_STATUS.GAP_DETECTED)) return PROTECTION_STATUS.GAP_DETECTED;
  if (statuses.includes(PROTECTION_STATUS.UNKNOWN)) return PROTECTION_STATUS.UNKNOWN;
  return PROTECTION_STATUS.ADEQUATE;
}

function caveatsFor({ policies, live, lapsed, life, health }) {
  const caveats = [];

  if (policies.length === 0) {
    caveats.push(
      'No policies are recorded, so nothing can be said about your cover either ' +
      'way. Adding them would let the app spot a gap.'
    );
    return caveats;
  }

  if (lapsed.length > 0) {
    caveats.push(
      `${lapsed.length} policy(ies) have passed their end date and are not ` +
      'counted as cover.'
    );
  }

  if (life.status === PROTECTION_STATUS.UNKNOWN || health.status === PROTECTION_STATUS.UNKNOWN) {
    caveats.push(
      'Some cover could not be judged, either because none of that kind is ' +
      'recorded or because there is no income baseline to measure against.'
    );
  }

  const withoutNominee = live.filter((policy) => policy.nomineeSet === false);
  if (withoutNominee.length > 0) {
    caveats.push(
      `${withoutNominee.length} policy(ies) have no nominee recorded. A payout ` +
      'without one can be slow or contested.'
    );
  }

  const unknownNominee = live.filter((policy) => policy.nomineeSet === null);
  if (unknownNominee.length > 0) {
    caveats.push(
      `Whether a nominee is named is not recorded for ${unknownNominee.length} ` +
      'policy(ies).'
    );
  }

  caveats.push(
    'Cover targets here are rules of thumb rather than a needs analysis for your ' +
    'household.'
  );

  return caveats;
}

module.exports = {
  assessProtection,
  readPolicy,
  gradeCover,
  worstOf,
  PROTECTION_STATUS,
  POLICY_TYPE,
  LIFE_TYPES,
  HEALTH_TYPES,
  LIFE_COVER_MULTIPLE,
  HEALTH_COVER_MONTHS_OF_ESSENTIALS,
  POLICY_VERSION
};
