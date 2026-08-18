/**
 * The three risk concepts, kept apart.
 *
 * Tolerance is how much loss somebody can live with. Capacity is how much loss
 * their finances can absorb without breaking. Need is how much risk the goal
 * they have set actually requires. These are routinely collapsed into one "risk
 * score", and the result is true of none of them — a comfortable investor with
 * no reserve and a mortgage has high tolerance and low capacity, and averaging
 * those into "moderate" describes a person who does not exist.
 *
 * The gate matters as much as the split. Nothing here is inferred from spending
 * habits or holdings. A profile exists only once the user has answered the
 * questions and confirmed the result; until then risk reads as unknown, and any
 * component depending on it is withheld rather than filled with a default.
 * Guessing here would be the difference between a suitable recommendation and an
 * unsuitable one presented with equal confidence.
 */
const money = require('./money');

const RISK_LEVEL = Object.freeze({ LOW: 'LOW', MODERATE: 'MODERATE', HIGH: 'HIGH' });

/** Bumped when the questions change — old answers did not answer new questions. */
const QUESTIONNAIRE_VERSION = '1.0.0';

/**
 * How long a profile stays current.
 *
 * Circumstances change, and a profile from three years ago describes somebody
 * who may since have had children or lost a job. Expiry is reported rather than
 * enforced: the honest response is to say it is old and ask, not to silently
 * discard what they told us.
 */
const PROFILE_MAX_AGE_DAYS = 730;

/**
 * Reads a stored assessment, refusing anything unconfirmed.
 *
 * Returns the same "unknown" shape whether the row is absent or merely
 * unconfirmed, because downstream the two mean the same thing: there is no
 * profile to reason from.
 */
function readRiskProfile(row, now) {
  if (!row || row.user_confirmed !== 1) {
    return {
      known: false,
      tolerance: null,
      capacity: null,
      need: null,
      reason: row
        ? 'A risk questionnaire was started but never confirmed.'
        : 'No risk profile has been completed.',
      questionnaireVersion: row?.questionnaire_version ?? null,
      assessedAt: row?.assessment_date ?? null,
      isStale: false
    };
  }

  const ageDays = (now - Number(row.assessment_date)) / 86_400_000;

  return {
    known: true,
    tolerance: row.risk_tolerance,
    capacity: row.risk_capacity,
    need: row.risk_need,
    reason: null,
    questionnaireVersion: row.questionnaire_version,
    assessedAt: Number(row.assessment_date),
    isStale: ageDays > PROFILE_MAX_AGE_DAYS,
    limitations: parseJson(row.limitations) ?? [],
    // Surfaced rather than resolved. Someone willing to take more risk than they
    // can afford is the single most important thing a risk profile can reveal,
    // and reconciling it into one number would erase it.
    mismatch: mismatchOf(row.risk_tolerance, row.risk_capacity)
  };
}

function mismatchOf(tolerance, capacity) {
  if (!tolerance || !capacity) return null;

  const rank = { LOW: 0, MODERATE: 1, HIGH: 2 };
  const gap = rank[tolerance] - rank[capacity];

  if (gap > 0) {
    return {
      kind: 'TOLERANCE_EXCEEDS_CAPACITY',
      note:
        'You are comfortable with more risk than your finances can currently ' +
        'absorb. Capacity is the binding one.'
    };
  }
  if (gap < 0) {
    return {
      kind: 'CAPACITY_EXCEEDS_TOLERANCE',
      note:
        'Your finances could absorb more risk than you are comfortable with. ' +
        'That is a perfectly reasonable place to be.'
    };
  }
  return null;
}

/**
 * Capacity worked out from the figures, offered as a starting point.
 *
 * Deliberately *not* written anywhere. Unlike tolerance, capacity is genuinely
 * observable — a large reserve and a small debt load really do mean more room to
 * absorb a loss — so the questionnaire can start from an informed suggestion
 * rather than a blank. But it is returned for the user to confirm or overrule,
 * never stored as though they had answered it.
 */
function suggestedCapacity({ coverageMonths, debtServiceRatio, incomeStability }) {
  if (coverageMonths === null || coverageMonths === undefined) {
    return { level: null, reason: 'Not enough is known about your reserve to suggest a starting point.' };
  }

  let score = 0;
  if (coverageMonths >= 6) score += 2;
  else if (coverageMonths >= 3) score += 1;

  if (debtServiceRatio !== null && debtServiceRatio !== undefined) {
    if (debtServiceRatio < 0.2) score += 1;
    else if (debtServiceRatio > 0.4) score -= 1;
  }

  if (incomeStability === 'STABLE') score += 1;
  else if (incomeStability === 'VOLATILE') score -= 1;

  const level = score >= 3 ? RISK_LEVEL.HIGH : (score >= 1 ? RISK_LEVEL.MODERATE : RISK_LEVEL.LOW);

  return {
    level,
    reason:
      'Suggested from your reserve, debt load and income steadiness. ' +
      'It is a starting point for you to confirm, not an answer on your behalf.'
  };
}

/**
 * The risk a goal requires, from the size of the gap and the time available.
 *
 * A large shortfall over a short horizon needs returns that only come with real
 * risk. Reporting that is not a recommendation to take it — often the honest
 * conclusion is that the goal needs changing rather than the portfolio.
 */
function requiredRiskFor({ shortfall, availableMonthly, monthsRemaining }) {
  if (!availableMonthly || money.isZero(availableMonthly) || monthsRemaining <= 0) {
    return { level: null, reason: 'Not enough is known to say what this goal would require.' };
  }

  const reachableBySaving = money.scaleBy(availableMonthly, monthsRemaining);
  if (money.compare(reachableBySaving, shortfall) >= 0) {
    return {
      level: RISK_LEVEL.LOW,
      reason: 'Reachable by saving alone, so it needs no investment risk.'
    };
  }

  const ratio = shortfall.minorUnits / Math.max(1, reachableBySaving.minorUnits);
  const level = ratio > 1.5 ? RISK_LEVEL.HIGH : RISK_LEVEL.MODERATE;

  return {
    level,
    reason:
      'Saving alone would not reach this in time, so it would need returns that ' +
      'carry risk — or a change to the amount or the date.'
  };
}

function parseJson(value) {
  if (value === null || value === undefined) return null;
  if (typeof value !== 'string') return value;
  try {
    return JSON.parse(value);
  } catch {
    return null;
  }
}

module.exports = {
  readRiskProfile,
  suggestedCapacity,
  requiredRiskFor,
  mismatchOf,
  RISK_LEVEL,
  QUESTIONNAIRE_VERSION,
  PROFILE_MAX_AGE_DAYS
};
