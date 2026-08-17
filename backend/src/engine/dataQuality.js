/**
 * How far the data supports the answer being asked for.
 *
 * Not a preprocessing detail. A precise figure computed from half the picture is
 * more dangerous than a rough one that says what it is missing, because the
 * precision is what makes it believable. So this is part of the assessment, not
 * a check that runs before it.
 *
 * Two things are reported and they are different: the six dimensions describe
 * the data itself, while readiness answers, per component, whether a particular
 * question can be answered at all. A user with no income recorded has poor
 * completeness *and* a perfectly answerable question about goal progress.
 */
const { READINESS, COMPONENT, assessAll } = require('./readiness');

const CONFIDENCE = Object.freeze({
  HIGH: 'HIGH',
  MEDIUM: 'MEDIUM',
  LOW: 'LOW',
  UNKNOWN: 'UNKNOWN'
});

/**
 * The domains an assessment can draw on, and where each comes from today.
 *
 * Domains with no table yet are declared here rather than omitted, so a
 * component that depends on one reports BLOCKED with a nameable reason instead
 * of quietly disappearing from the readiness map.
 */
const DOMAINS = Object.freeze([
  'income', 'expenses', 'commitments', 'goals',
  'assets', 'liabilities', 'insurance', 'riskProfile'
]);

/** Domains that have no store yet — arriving in later stages. */
const NOT_YET_COLLECTED = Object.freeze(['assets', 'liabilities', 'insurance', 'riskProfile']);

/**
 * Reduces raw rows to the presence-and-recency shape readiness needs.
 *
 * `newestAsOf` is the newest record's own date, not when it was read. A user who
 * imported six months of history yesterday has fresh *ingestion* and stale
 * *data*, and it is the second that decides whether a figure still describes
 * them.
 */
function domainPresence(rows, now) {
  const newest = (list, dateKey) => {
    let latest = null;
    for (const row of list) {
      const value = Number(row[dateKey]);
      if (Number.isFinite(value) && (latest === null || value > latest)) latest = value;
    }
    return latest;
  };

  const present = {
    income: {
      hasData: rows.incomes.length > 0,
      newestAsOf: newest(rows.incomes, 'income_date')
    },
    expenses: {
      hasData: rows.expenses.length > 0,
      newestAsOf: newest(rows.expenses, 'expense_date')
    },
    commitments: {
      hasData: rows.recurring.length > 0,
      // A commitment's recency is when it last took a payment; a subscription
      // nobody has paid in a year is stale however recently the row was edited.
      newestAsOf: newest(rows.recurring, 'last_occurrence_date') ?? now
    },
    goals: {
      hasData: rows.goals.length > 0,
      newestAsOf: rows.goals.length > 0 ? now : null
    }
  };

  for (const domain of NOT_YET_COLLECTED) {
    present[domain] = { hasData: false, newestAsOf: null };
  }

  return present;
}

/**
 * How much of the picture exists at all.
 *
 * Weighted towards what an assessment actually needs rather than counted flat:
 * income and expenses carry most of it because almost every question depends on
 * them, and no amount of insurance detail substitutes for not knowing what
 * someone earns.
 */
const DOMAIN_WEIGHT = Object.freeze({
  income: 3, expenses: 3, commitments: 2, goals: 1,
  assets: 2, liabilities: 2, insurance: 1, riskProfile: 1
});

function completeness(present) {
  const total = DOMAINS.reduce((sum, domain) => sum + DOMAIN_WEIGHT[domain], 0);
  const held = DOMAINS.reduce(
    (sum, domain) => sum + (present[domain]?.hasData ? DOMAIN_WEIGHT[domain] : 0),
    0
  );
  return { score: held / total, held, total };
}

/**
 * Whether related records agree with each other.
 *
 * The one check available at this stage: a live commitment that no transaction
 * has ever settled. It means either the commitment is wrong or payments are
 * being missed, and both matter — but it is reported rather than resolved,
 * because the engine cannot tell which without asking.
 */
function consistency(observedState) {
  const problems = [];

  const unsettled = observedState.commitments.live.filter(
    (entry) => entry.lastOccurrenceDate === null
  );
  if (unsettled.length > 0) {
    problems.push({
      kind: 'COMMITMENT_NEVER_SETTLED',
      detail: `${unsettled.length} tracked commitment(s) have no recorded payment.`,
      localIds: unsettled.map((entry) => entry.localId)
    });
  }

  if (observedState.commitments.awaitingPriceDecision.length > 0) {
    problems.push({
      kind: 'PRICE_CHANGE_UNANSWERED',
      detail:
        `${observedState.commitments.awaitingPriceDecision.length} commitment(s) have a ` +
        'price change waiting on the user. Figures use the amount they agreed.',
      localIds: observedState.commitments.awaitingPriceDecision.map((entry) => entry.localId)
    });
  }

  if (observedState.excludedCurrencies.length > 0) {
    problems.push({
      kind: 'OTHER_CURRENCY_EXCLUDED',
      detail:
        `Activity in ${observedState.excludedCurrencies.join(', ')} is not included; ` +
        'this product has no exchange rate source.',
      localIds: []
    });
  }

  return problems;
}

/**
 * Overall confidence in the assessment.
 *
 * Deliberately blunt — four levels, driven by whether anything is blocked and
 * how much is stale. A percentage here would imply a precision the inputs do not
 * have, which is the same false-precision problem that keeps this product away
 * from a single 0–100 health score.
 */
function overallConfidence(readiness, watermarks, completenessScore) {
  const statuses = Object.values(readiness).map((entry) => entry.status);
  const blocked = statuses.filter((status) => status === READINESS.BLOCKED).length;
  const degraded = statuses.filter((status) => status === READINESS.DEGRADED).length;

  // The server knowing its own copy is incomplete outranks everything else: the
  // figures may be arithmetically perfect and still describe only part of what
  // the user recorded.
  if (watermarks.completenessKnown && !watermarks.isComplete) return CONFIDENCE.LOW;
  if (blocked > statuses.length / 2) return CONFIDENCE.UNKNOWN;
  if (blocked > 0 || completenessScore < 0.5) return CONFIDENCE.LOW;
  if (degraded > 0 || completenessScore < 0.8) return CONFIDENCE.MEDIUM;
  return CONFIDENCE.HIGH;
}

/**
 * Plain sentences a reader needs before trusting the figures.
 *
 * Written out here rather than assembled at the surface, so the app, an export
 * and any later explanation all state the same limitation the same way.
 */
function caveatsFor(readiness, watermarks, problems) {
  const caveats = [];

  if (watermarks.completenessKnown && !watermarks.isComplete) {
    caveats.push(
      `This may not reflect ${watermarks.pendingLocalChanges} change(s) still ` +
      'waiting to sync from your device.'
    );
  } else if (!watermarks.completenessKnown) {
    caveats.push(
      'Your device did not report whether it had anything left to sync, so this ' +
      'may not include your most recent entries.'
    );
  }

  for (const [component, entry] of Object.entries(readiness)) {
    if (entry.status === READINESS.BLOCKED) {
      caveats.push(`${label(component)} cannot be worked out without ${list(entry.missing)}.`);
    } else if (entry.status === READINESS.DEGRADED) {
      caveats.push(`${label(component)} rests on ${list(entry.stale)} that may be out of date.`);
    }
  }

  problems.forEach((problem) => caveats.push(problem.detail));

  return caveats;
}

const LABELS = Object.freeze({
  [COMPONENT.CASH_FLOW]: 'Cash flow',
  [COMPONENT.DEBT_SERVICE]: 'Debt service',
  [COMPONENT.AFFORDABILITY]: 'Affordability',
  [COMPONENT.NET_WORTH]: 'Net worth',
  [COMPONENT.GOAL_PROGRESS]: 'Goal progress',
  [COMPONENT.INSURANCE_GAP]: 'Protection cover',
  [COMPONENT.PORTFOLIO_ALIGNMENT]: 'Portfolio alignment'
});

const DOMAIN_LABELS = Object.freeze({
  income: 'recorded income',
  expenses: 'recorded spending',
  commitments: 'tracked commitments',
  goals: 'savings goals',
  assets: 'what you own',
  liabilities: 'your loans',
  insurance: 'your policies',
  riskProfile: 'your risk profile'
});

const label = (component) => LABELS[component] ?? component;

function list(domains) {
  const named = domains.map((domain) => DOMAIN_LABELS[domain] ?? domain);
  if (named.length <= 1) return named[0] ?? 'more data';
  return `${named.slice(0, -1).join(', ')} and ${named[named.length - 1]}`;
}

function assessDataQuality({ rows, observedState, watermarks, now }) {
  const present = domainPresence(rows, now);
  const readiness = assessAll(present, now);
  const cover = completeness(present);
  const problems = consistency(observedState);

  return {
    readiness,
    completeness: cover,
    // Which domains exist at all, so a reader can see what the score is made of
    // rather than being handed a bare fraction.
    coverage: Object.fromEntries(
      DOMAINS.map((domain) => [domain, Boolean(present[domain]?.hasData)])
    ),
    freshness: Object.fromEntries(
      DOMAINS.map((domain) => [domain, present[domain]?.newestAsOf ?? null])
    ),
    consistency: problems,
    // Where the figures came from — a snapshot built entirely from statement
    // imports is a different kind of evidence to one the user typed themselves.
    verification: {
      commitmentsConfirmedByUser: observedState.commitments.live.length,
      goalsConfirmedByUser: observedState.goals.items.length,
      transactionsRecorded: observedState.outflow.transactionCount
    },
    confidence: overallConfidence(readiness, watermarks, cover.score),
    caveats: caveatsFor(readiness, watermarks, problems)
  };
}

module.exports = {
  assessDataQuality,
  domainPresence,
  completeness,
  consistency,
  overallConfidence,
  caveatsFor,
  CONFIDENCE,
  DOMAINS,
  NOT_YET_COLLECTED
};
