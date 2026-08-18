/**
 * Assembles the whole assessment once, so nothing computes it twice.
 *
 * Two endpoints need this now — the snapshot and the recommendations — and a
 * third arrives with the chat. If each built its own, they would drift: one
 * would gain a fix the others missed, and the app would start giving different
 * answers to the same question depending on which screen asked it. That is the
 * commonest way a system like this becomes untrustworthy, and it happens one
 * reasonable-looking divergence at a time.
 *
 * So the whole pipeline lives here, in one order, and callers take what they
 * need. Every run persists its snapshot, because a recommendation that cannot
 * point at the state it rested on cannot be explained afterwards.
 */
const crypto = require('crypto');
const { pool } = require('../config/db');
const money = require('../engine/money');
const {
  buildObservedState, sourceWatermarks, hashOf, ENGINE_VERSION, PAYLOAD_SCHEMA_VERSION
} = require('../engine/financialState');
const { assessDataQuality } = require('../engine/dataQuality');
const { assessCashFlow } = require('../engine/cashFlowEngine');
const { assessDebt } = require('../engine/debtEngine');
const { assessNetWorth, readAsset } = require('../engine/netWorthEngine');
const { assessEmergencyFund } = require('../engine/emergencyFundEngine');
const { assessGoals } = require('../engine/goalEngine');
const { assessProtection } = require('../engine/insuranceEngine');
const { assessPortfolio } = require('../engine/portfolioEngine');
const { readRiskProfile } = require('../engine/riskProfile');
const {
  projectGoalAcrossScenarios, findAssumption, ASSET_CLASS, HORIZON, SCENARIO
} = require('../engine/scenarioEngine');
const { completeMonths, DEFAULT_BASELINE_MONTHS } = require('../engine/periods');

/** Snapshots older than this are pruned on write, so history stays bounded. */
const KEEP_SNAPSHOTS = 50;

const SUPPORTED_CURRENCIES = ['INR', 'USD', 'EUR', 'GBP', 'JPY', 'AED', 'SGD', 'CAD', 'AUD', 'CHF'];

async function buildAssessmentContext({ uid, query = {}, now = Date.now() }) {
  const period = readPeriod(query, now);
  const currency = readCurrency(query);
  const timezone = readTimezone(query);

  const rows = await loadRows(uid, period);
  // Loaded before the quality assessment so it can see the domains they cover.
  const { loanRows, assetRows, policyRows, riskRow } = await loadHoldings(uid, currency);

  const observedState = buildObservedState({ rows, currency, period, timezone, now });
  const watermarks = sourceWatermarks({
    pendingLocalChanges: query.pendingLocalChanges,
    lastSuccessfulSyncAt: query.lastSuccessfulSyncAt,
    now
  });
  const dataQuality = assessDataQuality({
    rows: { ...rows, assets: assetRows, loans: loanRows },
    observedState,
    watermarks,
    now
  });

  // Cash flow reads a wider window than the requested period: a baseline needs
  // several complete months, and the period asked about is usually one. Rows are
  // scoped to the analysis currency first — the state builder reports what that
  // excluded, and below that boundary a mismatch is a fault rather than a filter.
  const baselineRows = await loadBaselineRows(uid, now, timezone);
  const scopedBaseline = scopedToCurrency(baselineRows, currency);
  const cashFlow = assessCashFlow({
    rows: scopedBaseline,
    currency,
    now,
    timezone,
    monthsBack: DEFAULT_BASELINE_MONTHS
  });

  const debt = assessDebt({
    loanRows,
    monthlyIncome: cashFlow.income.baseline,
    currency,
    now
  });

  const assets = assetRows.map((row) => readAsset(row, currency));
  const netWorth = assessNetWorth({ assetRows, debtAssessment: debt, currency, now });
  const emergencyFund = assessEmergencyFund({
    assets,
    expenseRows: scopedBaseline.expenses,
    months: completeMonths({ now, timeZone: timezone, count: DEFAULT_BASELINE_MONTHS }),
    incomeStability: cashFlow.income.stability.band,
    debtServiceRatio: debt.debtServiceRatio,
    currency,
    now
  });

  const assumptions = await loadAssumptions(uid);
  const baseInflation = findAssumption(assumptions, {
    scenarioType: SCENARIO.BASE,
    assetClass: ASSET_CLASS.NONE,
    horizonBand: HORIZON.ANY
  });

  const goals = assessGoals({
    goalRows: rows.goals,
    availableMonthly: cashFlow.obligations.uncommittedSurplus,
    currency,
    now,
    ...(baseInflation?.inflation_rate != null
      ? { inflationRate: Number(baseInflation.inflation_rate) / 100 }
      : {})
  });

  const scenarios = goals.goals
    .filter((goal) => goal.status === 'ACTIVE' && goal.monthsRemaining > 0)
    .map((goal) => projectGoalAcrossScenarios({
      goal: {
        localId: goal.localId,
        type: goal.type,
        targetAmount: goal.targetAsEntered,
        alreadySaved: goal.alreadySaved,
        monthsRemaining: goal.monthsRemaining,
        amountBasis: goal.amountBasis,
        // The same gate the goal engine applies, passed through rather than
        // re-derived, so a scenario cannot inflate what it would have left alone.
        inflateTarget: goal.amountBasis === 'TODAYS_MONEY',
        assetClass: ASSET_CLASS.BLENDED
      },
      assumptions,
      monthlyContribution: goal.committedMonthlyContribution,
      currency,
      now
    }));

  const protection = assessProtection({
    policyRows,
    monthlyIncome: cashFlow.income.baseline,
    essentialMonthlySpend: emergencyFund.essentialMonthlySpend,
    currency,
    now
  });

  const riskProfile = readRiskProfile(riskRow, now);
  const portfolio = assessPortfolio({ assets, riskProfile, currency, now });

  const snapshotId = crypto.randomUUID();
  const snapshotHash = hashOf(observedState);

  await persistSnapshot({
    snapshotId, uid, observedState, dataQuality, watermarks,
    snapshotHash, period, currency, timezone, now
  });

  return {
    uid,
    now,
    period,
    currency,
    timezone,
    snapshotId,
    snapshotHash,
    engineVersion: ENGINE_VERSION,
    payloadSchemaVersion: PAYLOAD_SCHEMA_VERSION,
    observedState,
    watermarks,
    dataQuality,
    cashFlow,
    debt,
    netWorth,
    emergencyFund,
    goals,
    scenarios,
    protection,
    portfolio,
    riskProfile,
    assumptions
  };
}

async function loadRows(uid, period) {
  const [expenses] = await pool.execute(
    `SELECT id, local_id, amount, currency, nature, category, expense_date
       FROM expenses
      WHERE uid = ? AND expense_date BETWEEN ? AND ?`,
    [uid, period.start, period.end]
  );

  const [incomes] = await pool.execute(
    `SELECT id, local_id, amount, currency, source, income_date
       FROM incomes
      WHERE uid = ? AND income_date BETWEEN ? AND ?`,
    [uid, period.start, period.end]
  );

  // Commitments and goals are not period-scoped: a commitment is a standing
  // obligation and a goal is a future target. Filtering either by the period
  // would make them vanish from any month they happened not to take a payment in.
  const [recurring] = await pool.execute(
    `SELECT local_id, title, amount, cadence, currency, nature, category, status,
            last_occurrence_date, next_due_date, pending_amount
       FROM recurring
      WHERE uid = ?`,
    [uid]
  );

  const [goals] = await pool.execute(
    `SELECT local_id, type, target_amount, target_date, current_saved, monthly_contribution,
            currency, amount_basis, priority, flexibility, status, funding_source
       FROM goals
      WHERE uid = ?`,
    [uid]
  );

  return { expenses, incomes, recurring, goals };
}

/** Rows over the baseline window, which reaches further back than the period. */
async function loadBaselineRows(uid, now, timezone) {
  const months = completeMonths({ now, timeZone: timezone, count: DEFAULT_BASELINE_MONTHS });
  const from = months[0].start;
  const to = months[months.length - 1].end;

  const [expenses] = await pool.execute(
    `SELECT id, local_id, amount, currency, nature, category, expense_date
       FROM expenses
      WHERE uid = ? AND expense_date BETWEEN ? AND ?`,
    [uid, from, to]
  );

  const [incomes] = await pool.execute(
    `SELECT id, local_id, amount, currency, source, income_date
       FROM incomes
      WHERE uid = ? AND income_date BETWEEN ? AND ?`,
    [uid, from, to]
  );

  const [recurring] = await pool.execute(
    `SELECT local_id, title, amount, cadence, currency, nature, category, status,
            last_occurrence_date, next_due_date, pending_amount
       FROM recurring
      WHERE uid = ?`,
    [uid]
  );

  return { expenses, incomes, recurring };
}

/**
 * Loan terms, holdings, policies and the risk profile.
 *
 * Currency-scoped in SQL rather than in memory: unlike transactions these have
 * no period to bound them, so an account holding a dozen foreign positions would
 * otherwise read them all just to discard them.
 */
async function loadHoldings(uid, currency) {
  const [loanRows] = await pool.execute(
    `SELECT recurring_local_id, principal_outstanding, outstanding_as_of, currency,
            interest_rate, rate_type, rate_reset_date, interest_compounding,
            scheduled_payment, payment_frequency, remaining_installments,
            next_payment_date, prepayment_charge_type, prepayment_charge_value,
            fees_or_penalties
       FROM loan_details
      WHERE uid = ? AND currency = ?`,
    [uid, currency]
  );

  const [assetRows] = await pool.execute(
    `SELECT local_id, label, asset_type, current_value, currency, valuation_date,
            liquidity_class, lock_in_until, ownership, verification_source
       FROM assets
      WHERE uid = ? AND currency = ?`,
    [uid, currency]
  );

  const [policyRows] = await pool.execute(
    `SELECT local_id, label, policy_type, provider, sum_assured, currency,
            premium_amount, premium_cadence, policy_end_date, nominee_set
       FROM insurance_policies
      WHERE uid = ? AND currency = ?`,
    [uid, currency]
  );

  // Not currency-scoped: a risk profile is not denominated in anything.
  const [riskRows] = await pool.execute(
    `SELECT risk_tolerance, risk_capacity, risk_need, questionnaire_version,
            answers_json, assessment_date, limitations, user_confirmed
       FROM risk_assessments
      WHERE uid = ?
      LIMIT 1`,
    [uid]
  );

  return { loanRows, assetRows, policyRows, riskRow: riskRows[0] ?? null };
}

/**
 * Approved assumptions available to this user.
 *
 * A user's own override sorts first, so the engine's first match is theirs where
 * one exists and the system default otherwise. The engine takes the first match,
 * so this ordering is what makes an override an override rather than a row that
 * is silently never read.
 */
async function loadAssumptions(uid) {
  const now = Date.now();
  const [rows] = await pool.execute(
    `SELECT id, uid, scenario_type, asset_class, time_horizon_band, jurisdiction,
            currency, inflation_rate, expected_return, income_growth_rate,
            expense_growth_rate, source, methodology, version, approval_status
       FROM assumption_sets
      WHERE approval_status = 'APPROVED'
        AND (uid IS NULL OR uid = ?)
        AND effective_from <= ?
        AND (effective_to IS NULL OR effective_to > ?)
      ORDER BY (uid IS NULL) ASC, id ASC`,
    [uid, now, now]
  );
  return rows;
}

async function persistSnapshot(args) {
  await pool.execute(
    `INSERT INTO financial_snapshots (
      snapshot_id, uid, observed_state_json, data_quality_json, source_watermarks_json,
      snapshot_hash, engine_version, payload_schema_version,
      computed_at, period_start, period_end, currency, timezone
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    [
      args.snapshotId,
      args.uid,
      JSON.stringify(args.observedState),
      JSON.stringify(args.dataQuality),
      JSON.stringify(args.watermarks),
      args.snapshotHash,
      ENGINE_VERSION,
      PAYLOAD_SCHEMA_VERSION,
      args.now,
      args.period.start,
      args.period.end,
      args.currency,
      args.timezone
    ]
  );

  // Kept bounded rather than forever. A snapshot is evidence for the assessment
  // that referenced it, not an archive of everything a user's finances have been.
  await pool.execute(
    `DELETE FROM financial_snapshots
      WHERE uid = ?
        AND snapshot_id NOT IN (
          SELECT snapshot_id FROM (
            SELECT snapshot_id FROM financial_snapshots
             WHERE uid = ? ORDER BY computed_at DESC LIMIT ${KEEP_SNAPSHOTS}
          ) AS keep
        )`,
    [args.uid, args.uid]
  );
}

/** Drops anything denominated differently, so the engines never see a mix. */
const scopedToCurrency = (rows, currency) => ({
  expenses: rows.expenses.filter((row) => row.currency === currency),
  incomes: rows.incomes.filter((row) => row.currency === currency),
  recurring: rows.recurring.filter((row) => row.currency === currency)
});

/**
 * Money leaves as a decimal string plus its minor units.
 *
 * Both, deliberately: the string is what a client should display or store, and
 * the minor units are what any further arithmetic must use. Sending only a
 * decimal would invite a client to parse it back into a float, which is the
 * thing this engine exists to avoid.
 */
function presentable(value) {
  if (value === null || typeof value !== 'object') return value;
  if (Array.isArray(value)) return value.map(presentable);

  if (isMoney(value)) {
    return {
      minorUnits: value.minorUnits,
      currency: value.currency,
      amount: money.toDecimalString(value)
    };
  }

  return Object.fromEntries(
    Object.entries(value).map(([key, entry]) => [key, presentable(entry)])
  );
}

const isMoney = (value) =>
  Object.keys(value).length === 2 &&
  typeof value.minorUnits === 'number' &&
  typeof value.currency === 'string';

function readPeriod(query, now) {
  const start = Number(query.periodStart);
  const end = Number(query.periodEnd);

  if (!Number.isFinite(start) || !Number.isFinite(end)) {
    // Defaults to the last 30 days rather than failing: a client asking "how am
    // I doing" without naming a window is a reasonable request.
    return { start: now - 30 * 86_400_000, end: now };
  }
  if (end < start) throw badRequest('periodEnd is before periodStart.');
  if (end - start > 400 * 86_400_000) {
    throw badRequest('Period is longer than the engine will assess in one snapshot.');
  }
  return { start, end };
}

function readCurrency(query) {
  const code = String(query.currency || 'INR').toUpperCase();
  if (!SUPPORTED_CURRENCIES.includes(code)) throw badRequest(`Unsupported currency: ${code}`);
  return code;
}

function readTimezone(query) {
  const zone = String(query.timezone || 'Asia/Kolkata');
  // Validated against the platform's own database rather than a pattern: an
  // unrecognised zone silently becoming UTC would move every period boundary.
  try {
    new Intl.DateTimeFormat('en', { timeZone: zone });
    return zone;
  } catch {
    throw badRequest(`Unknown timezone: ${zone}`);
  }
}

function badRequest(message) {
  const error = new Error(message);
  error.status = 400;
  return error;
}

module.exports = {
  buildAssessmentContext,
  presentable,
  readPeriod,
  readCurrency,
  readTimezone,
  SUPPORTED_CURRENCIES,
  KEEP_SNAPSHOTS
};
