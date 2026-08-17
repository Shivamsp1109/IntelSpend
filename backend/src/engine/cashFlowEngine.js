/**
 * What comes in, what goes out, and how steady either of them is.
 *
 * Built from complete calendar months only. A part-month baseline is the most
 * dangerous figure this engine could produce, because it is wrong in the
 * flattering direction: on the 3rd the salary has landed and the rent has not,
 * so a naive average reports someone as saving most of their income and every
 * downstream answer inherits it.
 *
 * The baseline is a median rather than a mean, and that is doing real work. An
 * annual insurance premium or a bonus lands in one month out of six; a mean
 * spreads it across all of them and reports a monthly figure the user never
 * actually experiences, while a median steps over it and describes the ordinary
 * month. The spread is reported separately, so the unusual month is visible
 * rather than smoothed away.
 *
 * Nothing here mixes currencies. The engine is handed rows already scoped to one
 * currency by the state builder, which reports what it left out; below that
 * boundary a mismatch is a fault in the code and is thrown rather than skipped —
 * silently dropping a user's foreign income would understate what they earn and
 * the resulting savings rate would look entirely reasonable.
 */
const money = require('./money');
const { provenance, SOURCE_TYPE } = require('./provenance');
const { TREND_MIN_MONTHS, completeMonths, currentPartialMonth, windowOf } = require('./periods');
const { OUTFLOW_NATURES, NATURE, RECURRING_STATUS, PERIODS_PER_MONTH } = require('./constants');

/**
 * How steady a stream is, from its coefficient of variation.
 *
 * Named bands rather than a raw number because the number invites false
 * precision: the difference between 0.31 and 0.34 is noise, the difference
 * between a salaried month and a freelance one is not.
 */
const STABILITY = Object.freeze({
  STABLE: 'STABLE',
  VARIABLE: 'VARIABLE',
  VOLATILE: 'VOLATILE',
  UNKNOWN: 'UNKNOWN'
});

const VARIABLE_ABOVE = 0.15;
const VOLATILE_ABOVE = 0.40;

/** A due date this far past with nothing recorded against it counts as missed. */
const OVERDUE_GRACE_DAYS = 5;

function assertSingleCurrency(rows, currency, label) {
  for (const row of rows) {
    if (row.currency !== currency) {
      throw new Error(
        `${label} contains ${row.currency} but the assessment is in ${currency}. ` +
        'This product has no exchange rate source; scope the rows before ' +
        'computing, and report what was excluded.'
      );
    }
  }
}

/** Totals one month's rows, keyed by the month they fall in. */
function bucketByMonth(rows, months, dateKey, amountOf, currency) {
  const buckets = new Map(months.map((month) => [month.key, {
    month,
    total: money.zero(currency),
    count: 0,
    recordIds: []
  }]));

  for (const row of rows) {
    const when = Number(row[dateKey]);
    const month = months.find((candidate) => when >= candidate.start && when <= candidate.end);
    if (!month) continue;

    const bucket = buckets.get(month.key);
    bucket.total = money.add(bucket.total, amountOf(row));
    bucket.count += 1;
    if (row.id !== undefined) bucket.recordIds.push(row.id);
  }

  return [...buckets.values()];
}

/**
 * The middle value, which is what "typical" should mean here.
 *
 * Even counts average the two middle months rather than picking one, so the
 * result does not jump depending on which side a tie falls.
 */
function median(amounts, currency) {
  if (amounts.length === 0) return money.zero(currency);

  const sorted = [...amounts].sort((a, b) => a.minorUnits - b.minorUnits);
  const middle = Math.floor(sorted.length / 2);

  if (sorted.length % 2 === 1) return sorted[middle];
  return money.scaleBy(money.add(sorted[middle - 1], sorted[middle]), 0.5);
}

function mean(amounts, currency) {
  if (amounts.length === 0) return money.zero(currency);
  return money.scaleBy(money.sum(amounts, currency), 1 / amounts.length);
}

/**
 * Coefficient of variation — spread relative to size.
 *
 * Relative rather than absolute so it means the same thing at every income
 * level: ₹5,000 of month-to-month swing is noise on ₹2,00,000 and upheaval on
 * ₹20,000, and a standard deviation alone cannot tell those apart.
 */
function coefficientOfVariation(amounts) {
  if (amounts.length < 2) return null;

  const values = amounts.map((amount) => amount.minorUnits);
  const average = values.reduce((sum, value) => sum + value, 0) / values.length;
  if (average === 0) return null;

  const variance = values.reduce((sum, value) => sum + (value - average) ** 2, 0) / values.length;
  return Math.sqrt(variance) / Math.abs(average);
}

/**
 * Grades steadiness, and says plainly when it cannot.
 *
 * Volatility is reported, never treated as a fault. Freelance income is
 * genuinely irregular and that is a fact about the work, not a data problem —
 * blocking on it would lock out exactly the people whose cash flow is hardest to
 * plan and who most need the help.
 */
function stabilityOf(amounts) {
  const variation = coefficientOfVariation(amounts);
  if (variation === null) {
    return { band: STABILITY.UNKNOWN, coefficientOfVariation: null };
  }
  const band = variation > VOLATILE_ABOVE
    ? STABILITY.VOLATILE
    : (variation > VARIABLE_ABOVE ? STABILITY.VARIABLE : STABILITY.STABLE);

  return { band, coefficientOfVariation: variation };
}

/**
 * Live commitments, normalised to what they cost in an average month, plus any
 * whose due date has passed with nothing recorded against them.
 *
 * Overdue items are surfaced rather than added to the month's obligations: the
 * engine cannot tell a genuinely missed payment from one the user made and has
 * not imported yet, and quietly assuming either would be a claim about their
 * finances that nothing supports.
 */
function summariseObligations(recurringRows, currency, now) {
  assertSingleCurrency(recurringRows.filter(isLive), currency, 'Recurring commitments');

  let monthlyTotal = money.zero(currency);
  let monthlyDebt = money.zero(currency);
  const overdue = [];

  for (const row of recurringRows) {
    if (!isLive(row)) continue;

    const perMonth = PERIODS_PER_MONTH[row.cadence];
    if (perMonth === undefined) continue;

    const agreed = money.fromLegacyDouble(Number(row.amount), currency);
    const monthlyEquivalent = money.scaleBy(agreed, perMonth);

    monthlyTotal = money.add(monthlyTotal, monthlyEquivalent);
    if (row.nature === NATURE.LOAN_REPAYMENT) {
      monthlyDebt = money.add(monthlyDebt, monthlyEquivalent);
    }

    const due = row.next_due_date;
    if (due && due < now - OVERDUE_GRACE_DAYS * 86_400_000) {
      overdue.push({
        localId: row.local_id,
        title: row.title,
        amount: agreed,
        dueAt: due,
        daysOverdue: Math.floor((now - due) / 86_400_000)
      });
    }
  }

  return { monthlyTotal, monthlyDebt, overdue };
}

const isLive = (row) => row.status === RECURRING_STATUS.ACTIVE;

/**
 * The cash-flow assessment for one user.
 *
 * @param rows      { expenses, incomes, recurring } already scoped to `currency`
 * @param currency  the analysis currency
 * @param now       epoch millis
 * @param timezone  IANA zone the user's calendar months are measured in
 * @param months    how many complete months to look back over
 */
function assessCashFlow({ rows, currency, now, timezone, monthsBack }) {
  assertSingleCurrency(rows.incomes, currency, 'Income rows');
  assertSingleCurrency(rows.expenses, currency, 'Expense rows');

  const months = completeMonths({ now, timeZone: timezone, count: monthsBack });
  const excluded = currentPartialMonth({ now, timeZone: timezone });

  const incomeBuckets = bucketByMonth(
    rows.incomes, months, 'income_date',
    (row) => money.fromLegacyDouble(Number(row.amount), currency),
    currency
  );

  // Only the natures that represent money genuinely leaving. Self-transfers and
  // card bill payments move money that is counted elsewhere.
  const spendingRows = rows.expenses.filter((row) => OUTFLOW_NATURES.includes(row.nature));
  const outflowBuckets = bucketByMonth(
    spendingRows, months, 'expense_date',
    (row) => money.fromLegacyDouble(Number(row.amount), currency),
    currency
  );

  // A month with no rows at all is a gap in the record, not a month of zero
  // income. Averaging over it would drag the baseline down by however many
  // months the user simply had not imported yet.
  const monthsWithoutData = months
    .filter((month) => {
      const income = incomeBuckets.find((bucket) => bucket.month.key === month.key);
      const outflow = outflowBuckets.find((bucket) => bucket.month.key === month.key);
      return income.count === 0 && outflow.count === 0;
    })
    .map((month) => month.key);

  const observedMonths = months.filter((month) => !monthsWithoutData.includes(month.key));
  const observedKeys = new Set(observedMonths.map((month) => month.key));
  const observedIncome = incomeBuckets.filter((bucket) => observedKeys.has(bucket.month.key));
  const observedOutflow = outflowBuckets.filter((bucket) => observedKeys.has(bucket.month.key));

  const incomeAmounts = observedIncome.map((bucket) => bucket.total);
  const outflowAmounts = observedOutflow.map((bucket) => bucket.total);

  const incomeBaseline = median(incomeAmounts, currency);
  const outflowBaseline = median(outflowAmounts, currency);
  const surplus = money.subtract(incomeBaseline, outflowBaseline);

  const obligations = summariseObligations(rows.recurring, currency, now);

  return {
    currency,
    timezone,

    // The window is part of the answer. A figure without the period it covers
    // cannot be checked, and cannot be compared with the next one.
    window: windowOf(months),
    excludedPartialMonth: {
      key: excluded.key,
      fractionElapsed: excluded.fractionElapsed,
      reason:
        'The current month is not over. Including it would count a full salary ' +
        'against part of a month of spending.'
    },
    monthsObserved: observedMonths.length,
    monthsWithoutData,

    income: {
      baseline: incomeBaseline,
      mean: mean(incomeAmounts, currency),
      stability: stabilityOf(incomeAmounts),
      byMonth: observedIncome.map(asMonthTotal),
      provenance: provenance({
        sourceType: SOURCE_TYPE.IMPORTED,
        sourceRecordIds: observedIncome.flatMap((bucket) => bucket.recordIds),
        asOf: now
      })
    },

    outflow: {
      baseline: outflowBaseline,
      mean: mean(outflowAmounts, currency),
      stability: stabilityOf(outflowAmounts),
      byMonth: observedOutflow.map(asMonthTotal),
      provenance: provenance({
        sourceType: SOURCE_TYPE.IMPORTED,
        sourceRecordIds: observedOutflow.flatMap((bucket) => bucket.recordIds),
        asOf: now
      })
    },

    surplus,
    // Null rather than zero when nothing came in: "what share of no income did
    // you keep" has no answer, and any figure there would be a claim.
    savingsRate: money.ratio(surplus, incomeBaseline),

    obligations: {
      monthlyTotal: obligations.monthlyTotal,
      monthlyDebtTotal: obligations.monthlyDebt,
      // What is left after the standing obligations are met, which is the figure
      // an affordability question actually needs.
      uncommittedSurplus: money.subtract(surplus, obligations.monthlyTotal),
      overdue: obligations.overdue,
      provenance: provenance({
        sourceType: SOURCE_TYPE.CONFIRMED,
        sourceRecordIds: rows.recurring.filter(isLive).map((row) => row.local_id),
        asOf: now
      })
    },

    // Whether the shape of these figures is a pattern or just a period. One
    // month is never a trend, however clean it looks.
    trendIsMeaningful: observedMonths.length >= TREND_MIN_MONTHS,
    caveats: caveatsFor({
      observedMonths, monthsWithoutData, incomeAmounts,
      incomeBaseline, obligations, excluded
    })
  };
}

const asMonthTotal = (bucket) => ({
  key: bucket.month.key,
  total: bucket.total,
  transactionCount: bucket.count
});

function caveatsFor({ observedMonths, monthsWithoutData, incomeAmounts, incomeBaseline, obligations, excluded }) {
  const caveats = [];

  if (observedMonths.length === 0) {
    caveats.push(
      'No complete month of data yet, so there is no baseline to work from. ' +
      `${excluded.key} is still in progress and is not counted.`
    );
    return caveats;
  }

  if (observedMonths.length < TREND_MIN_MONTHS) {
    caveats.push(
      `Based on ${observedMonths.length} complete month(s). That is a period, ` +
      'not yet a pattern — treat it as a starting point rather than a trend.'
    );
  }

  if (monthsWithoutData.length > 0) {
    caveats.push(
      `No records at all for ${monthsWithoutData.join(', ')}. Those months are ` +
      'left out rather than counted as months of nothing.'
    );
  }

  if (money.isZero(incomeBaseline)) {
    caveats.push('No income is recorded for these months, so savings rate cannot be worked out.');
  }

  const stability = stabilityOf(incomeAmounts);
  if (stability.band === STABILITY.VOLATILE) {
    caveats.push(
      'Your income varies a lot month to month, so a single monthly figure ' +
      'describes it loosely. The typical month is a safer guide than the average.'
    );
  }

  if (obligations.overdue.length > 0) {
    caveats.push(
      `${obligations.overdue.length} tracked commitment(s) are past due with ` +
      'nothing recorded against them. They may have been paid without being imported.'
    );
  }

  return caveats;
}

module.exports = {
  assessCashFlow,
  summariseObligations,
  stabilityOf,
  coefficientOfVariation,
  median,
  mean,
  bucketByMonth,
  assertSingleCurrency,
  STABILITY,
  VARIABLE_ABOVE,
  VOLATILE_ABOVE,
  OVERDUE_GRACE_DAYS
};
