/**
 * The canonical observed state: what is true, as distinct from what is assumed.
 *
 * Facts only. Income and expenses that were recorded, commitments the user
 * confirmed, goals they set. No projection, no assumption, no inference — those
 * belong to later stages and are kept apart deliberately, because a figure that
 * mixes the two cannot be checked by the person it describes.
 *
 * The result is serialised into a snapshot row and never updated, so a
 * recommendation made today can still show the figures it rested on after the
 * underlying transactions have moved on.
 *
 * Every amount here is in whole minor units (see engine/money). The tables it
 * reads are still DOUBLE — the conversion happens once, here, at the boundary.
 */
const crypto = require('crypto');
const money = require('./money');
const { provenance, SOURCE_TYPE } = require('./provenance');
const {
  ENGINE_VERSION,
  PAYLOAD_SCHEMA_VERSION,
  NATURE,
  OUTFLOW_NATURES,
  RECURRING_STATUS,
  PERIODS_PER_MONTH
} = require('./constants');

/**
 * Groups the period's transactions by how the money moved.
 *
 * Split rather than summed into one total because the natures answer different
 * questions: rent is consumption, an EMI repays a debt, a mutual-fund
 * contribution buys an asset the user still owns. A household with a large
 * figure under the third is in a very different position to one with a large
 * figure under the second, and a single "outgoings" number describes neither.
 *
 * SelfTransfer, CreditCardPayment and CashWithdrawal are deliberately absent
 * from the totals: each moves money that is either counted elsewhere or not yet
 * known to have been spent, and counting them again would record the same rupee
 * twice.
 */
function summariseTransactions(rows, currency) {
  const byNature = {};
  for (const nature of OUTFLOW_NATURES) {
    byNature[nature] = money.zero(currency);
  }

  let counted = 0;
  const contributingIds = [];

  for (const row of rows) {
    if (row.currency !== currency) continue;
    if (!OUTFLOW_NATURES.includes(row.nature)) continue;

    byNature[row.nature] = money.add(
      byNature[row.nature],
      money.fromLegacyDouble(Number(row.amount), currency)
    );
    contributingIds.push(row.id);
    counted += 1;
  }

  return { byNature, counted, contributingIds };
}

function sumIncome(rows, currency) {
  let total = money.zero(currency);
  const contributingIds = [];

  for (const row of rows) {
    if (row.currency !== currency) continue;
    total = money.add(total, money.fromLegacyDouble(Number(row.amount), currency));
    contributingIds.push(row.id);
  }

  return { total, contributingIds };
}

/**
 * What live commitments cost in an average month.
 *
 * Cadence-normalised before summing: a weekly ₹1,000 and a yearly ₹12,000 added
 * as ₹13,000 of monthly obligation is wrong by a factor of four in one direction
 * and twelve in the other.
 *
 * Paused and ended commitments are excluded here rather than at each caller, so
 * a later module cannot forget and start counting a cancelled subscription
 * towards what the user owes.
 *
 * The amount used is always the one the user agreed to. A commitment carrying an
 * unanswered price change keeps its confirmed figure and the open question is
 * reported separately — the engine does not get to decide that for them.
 */
function summariseCommitments(rows, currency) {
  let monthlyTotal = money.zero(currency);
  let monthlyDebt = money.zero(currency);
  const live = [];
  const awaitingPriceDecision = [];

  for (const row of rows) {
    if (row.currency !== currency) continue;
    if (row.status !== RECURRING_STATUS.ACTIVE) continue;

    const perMonth = PERIODS_PER_MONTH[row.cadence];
    if (perMonth === undefined) continue;

    const agreed = money.fromLegacyDouble(Number(row.amount), currency);
    const monthlyEquivalent = money.scaleBy(agreed, perMonth);

    monthlyTotal = money.add(monthlyTotal, monthlyEquivalent);
    if (row.nature === NATURE.LOAN_REPAYMENT) {
      monthlyDebt = money.add(monthlyDebt, monthlyEquivalent);
    }

    live.push({
      localId: row.local_id,
      title: row.title,
      amount: agreed,
      monthlyEquivalent,
      cadence: row.cadence,
      nature: row.nature,
      category: row.category,
      nextDueDate: row.next_due_date ?? null,
      lastOccurrenceDate: row.last_occurrence_date ?? null
    });

    if (row.pending_amount !== null && row.pending_amount !== undefined) {
      awaitingPriceDecision.push({
        localId: row.local_id,
        title: row.title,
        agreed,
        proposed: money.fromLegacyDouble(Number(row.pending_amount), currency)
      });
    }
  }

  return { monthlyTotal, monthlyDebt, live, awaitingPriceDecision };
}

function summariseGoals(rows, currency) {
  return rows.map((row) => ({
    localId: row.local_id,
    type: row.type,
    targetAmount: money.fromLegacyDouble(Number(row.target_amount), currency),
    currentSaved: money.fromLegacyDouble(Number(row.current_saved), currency),
    targetDate: row.target_date,
    // The figure the user committed to, not one the engine worked out. Carried
    // apart so a suggestion can never quietly replace their own decision.
    monthlyContribution: money.fromLegacyDouble(Number(row.monthly_contribution), currency)
  }));
}

/**
 * Assembles observed state from rows already fetched for one user and period.
 *
 * Takes rows rather than querying, so the whole thing is testable against
 * hand-written data with no database.
 */
function buildObservedState({ rows, currency, period, timezone, now }) {
  const income = sumIncome(rows.incomes, currency);
  const transactions = summariseTransactions(rows.expenses, currency);
  const commitments = summariseCommitments(rows.recurring, currency);
  const goals = summariseGoals(rows.goals, currency);

  // Currencies present but left out. The product has no exchange-rate source,
  // so an assessment covers one currency and says what it excluded rather than
  // under-reporting without explanation.
  const excludedCurrencies = [...new Set(
    [...rows.expenses, ...rows.incomes]
      .map((row) => row.currency)
      .filter((code) => code && code !== currency)
  )].sort();

  const outflow = OUTFLOW_NATURES.reduce(
    (running, nature) => money.add(running, transactions.byNature[nature]),
    money.zero(currency)
  );

  return {
    currency,
    timezone,
    period: { start: period.start, end: period.end },
    asOf: now,

    income: {
      total: income.total,
      provenance: provenance({
        sourceType: SOURCE_TYPE.IMPORTED,
        sourceRecordIds: income.contributingIds,
        asOf: now
      })
    },

    outflow: {
      total: outflow,
      byNature: transactions.byNature,
      transactionCount: transactions.counted,
      provenance: provenance({
        sourceType: SOURCE_TYPE.IMPORTED,
        sourceRecordIds: transactions.contributingIds,
        asOf: now
      })
    },

    commitments: {
      monthlyTotal: commitments.monthlyTotal,
      monthlyDebtTotal: commitments.monthlyDebt,
      live: commitments.live,
      awaitingPriceDecision: commitments.awaitingPriceDecision,
      provenance: provenance({
        sourceType: SOURCE_TYPE.CONFIRMED,
        sourceRecordIds: commitments.live.map((entry) => entry.localId),
        asOf: now
      })
    },

    goals: {
      items: goals,
      provenance: provenance({
        sourceType: SOURCE_TYPE.CONFIRMED,
        sourceRecordIds: goals.map((goal) => goal.localId),
        asOf: now
      })
    },

    excludedCurrencies
  };
}

/**
 * Serialises deterministically, so the same state always hashes the same.
 *
 * `JSON.stringify` preserves insertion order, which means two states holding
 * identical figures assembled in a different order would hash differently and
 * look like a change nobody made. Sorting keys at every level removes that.
 */
function canonicalise(value) {
  if (value === null || typeof value !== 'object') return value;
  if (Array.isArray(value)) return value.map(canonicalise);

  return Object.keys(value)
    .sort()
    .reduce((ordered, key) => {
      ordered[key] = canonicalise(value[key]);
      return ordered;
    }, {});
}

function hashOf(observedState) {
  return crypto
    .createHash('sha256')
    .update(JSON.stringify(canonicalise(observedState)))
    .digest('hex');
}

/**
 * What the server knew about its own completeness when the snapshot was taken.
 *
 * The product is offline-first: rows live on the device until a sweep uploads
 * them, so the server's copy is a lower bound on what the user has actually
 * recorded, never automatically the whole of it. The device reports what it is
 * still holding, and that report is stored alongside the figures rather than
 * discarded — an assessment that was built while three expenses were unsynced
 * should still say so a month later.
 */
function sourceWatermarks({ pendingLocalChanges, lastSuccessfulSyncAt, now }) {
  const pending = Number.isFinite(Number(pendingLocalChanges))
    ? Math.max(0, Math.trunc(Number(pendingLocalChanges)))
    : null;

  return {
    pendingLocalChanges: pending,
    lastSuccessfulSyncAt: Number.isFinite(Number(lastSuccessfulSyncAt))
      ? Number(lastSuccessfulSyncAt)
      : null,
    serverObservedAt: now,
    // Null means the client did not say. Treated as unknown rather than zero:
    // "nothing is pending" is a claim, and an absent report is not that claim.
    completenessKnown: pending !== null,
    isComplete: pending === 0
  };
}

module.exports = {
  buildObservedState,
  summariseTransactions,
  summariseCommitments,
  summariseGoals,
  sumIncome,
  sourceWatermarks,
  canonicalise,
  hashOf,
  ENGINE_VERSION,
  PAYLOAD_SCHEMA_VERSION
};
