const express = require('express');
const crypto = require('crypto');
const { pool } = require('../config/db');
const { requireFirebaseAuth } = require('../middleware/auth');
const { syncLimiter } = require('../middleware/rateLimit');
const money = require('../engine/money');
const {
  buildObservedState,
  sourceWatermarks,
  hashOf,
  ENGINE_VERSION,
  PAYLOAD_SCHEMA_VERSION
} = require('../engine/financialState');
const { assessDataQuality } = require('../engine/dataQuality');
const { assessCashFlow } = require('../engine/cashFlowEngine');
const { completeMonths, DEFAULT_BASELINE_MONTHS } = require('../engine/periods');
const { COMPONENT } = require('../engine/readiness');

const router = express.Router();

/** Snapshots older than this are pruned on write, so history stays bounded. */
const KEEP_SNAPSHOTS = 50;

/**
 * GET /financial-state/snapshot
 *
 * Builds and stores what the engine sees for one user and period.
 *
 * Query: periodStart, periodEnd (epoch millis), currency, timezone,
 *        pendingLocalChanges, lastSuccessfulSyncAt
 *
 * The last two matter more than they look. This product is offline-first — rows
 * live on the handset until a sweep uploads them — so the server's copy is a
 * lower bound on what the user has actually recorded, never automatically the
 * whole of it. The device says what it is still holding, and that goes into the
 * snapshot rather than being discarded, so an assessment built while three
 * expenses were unsynced still says so when someone reads it later.
 */
router.get('/snapshot', requireFirebaseAuth, syncLimiter, async (req, res, next) => {
  try {
    const uid = req.user.uid;
    const now = Date.now();
    const period = readPeriod(req.query, now);
    const currency = readCurrency(req.query);
    const timezone = readTimezone(req.query);

    const rows = await loadRows(uid, period);

    const observedState = buildObservedState({ rows, currency, period, timezone, now });
    const watermarks = sourceWatermarks({
      pendingLocalChanges: req.query.pendingLocalChanges,
      lastSuccessfulSyncAt: req.query.lastSuccessfulSyncAt,
      now
    });
    const dataQuality = assessDataQuality({ rows, observedState, watermarks, now });

    // Cash flow reads a wider window than the requested period: a baseline needs
    // several complete months, and the period a caller asks about is usually one.
    // Rows are scoped to the analysis currency first — the state builder reports
    // what that excluded, and below that boundary a mismatch is a fault, so the
    // engine throws rather than quietly dropping a figure.
    const baselineRows = await loadBaselineRows(uid, now, timezone);
    const cashFlow = assessCashFlow({
      rows: scopedToCurrency(baselineRows, currency),
      currency,
      now,
      timezone,
      monthsBack: DEFAULT_BASELINE_MONTHS
    });

    const snapshotId = crypto.randomUUID();
    const snapshotHash = hashOf(observedState);

    await persist({
      snapshotId, uid, observedState, dataQuality, watermarks,
      snapshotHash, period, currency, timezone, now
    });

    return res.json({
      snapshotId,
      snapshotHash,
      engineVersion: ENGINE_VERSION,
      payloadSchemaVersion: PAYLOAD_SCHEMA_VERSION,
      computedAt: now,
      period: { start: period.start, end: period.end },
      currency,
      timezone,
      observedState: presentable(observedState),
      dataQuality,
      // Carried with its readiness rather than as a bare figure, so a consumer
      // cannot present a baseline built on missing income as though it stood.
      cashFlow: {
        readiness: dataQuality.readiness[COMPONENT.CASH_FLOW],
        ...presentable(cashFlow)
      },
      sourceWatermarks: watermarks
    });
  } catch (error) {
    return next(error);
  }
});

/**
 * Rows over the baseline window, which reaches further back than the period
 * being reported on.
 */
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

/** Drops anything denominated differently, so the engine below never sees a mix. */
const scopedToCurrency = (rows, currency) => ({
  expenses: rows.expenses.filter((row) => row.currency === currency),
  incomes: rows.incomes.filter((row) => row.currency === currency),
  recurring: rows.recurring.filter((row) => row.currency === currency)
});

/** GET /financial-state/snapshot/:snapshotId — reads one back, unchanged. */
router.get('/snapshot/:snapshotId', requireFirebaseAuth, syncLimiter, async (req, res, next) => {
  try {
    const [rows] = await pool.execute(
      `SELECT snapshot_id            AS snapshotId,
              observed_state_json    AS observedState,
              data_quality_json      AS dataQuality,
              source_watermarks_json AS sourceWatermarks,
              snapshot_hash          AS snapshotHash,
              engine_version         AS engineVersion,
              payload_schema_version AS payloadSchemaVersion,
              computed_at            AS computedAt,
              period_start           AS periodStart,
              period_end             AS periodEnd,
              currency,
              timezone
         FROM financial_snapshots
        WHERE snapshot_id = ? AND uid = ?
        LIMIT 1`,
      [req.params.snapshotId, req.user.uid]
    );

    if (rows.length === 0) {
      const error = new Error('No such snapshot.');
      error.status = 404;
      throw error;
    }

    const row = rows[0];
    return res.json({
      snapshotId: row.snapshotId,
      snapshotHash: row.snapshotHash,
      engineVersion: row.engineVersion,
      payloadSchemaVersion: row.payloadSchemaVersion,
      computedAt: Number(row.computedAt),
      period: { start: Number(row.periodStart), end: Number(row.periodEnd) },
      currency: row.currency,
      timezone: row.timezone,
      observedState: parse(row.observedState),
      dataQuality: parse(row.dataQuality),
      sourceWatermarks: parse(row.sourceWatermarks)
    });
  } catch (error) {
    return next(error);
  }
});

/** mysql2 returns JSON columns already parsed on some versions and as text on others. */
function parse(value) {
  return typeof value === 'string' ? JSON.parse(value) : value;
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
  // being assessed would make them vanish from any month they happened not to
  // take a payment in.
  const [recurring] = await pool.execute(
    `SELECT local_id, title, amount, cadence, currency, nature, category, status,
            last_occurrence_date, next_due_date, pending_amount
       FROM recurring
      WHERE uid = ?`,
    [uid]
  );

  const [goals] = await pool.execute(
    `SELECT local_id, type, target_amount, target_date, current_saved, monthly_contribution
       FROM goals
      WHERE uid = ?`,
    [uid]
  );

  return { expenses, incomes, recurring, goals };
}

async function persist(args) {
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
  // that referenced it, not an archive of everything the user's finances have
  // ever been, and this table grows on every request.
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
    // Defaults to the last 30 days rather than failing: a client asking for
    // "how am I doing" without naming a window is a reasonable request.
    return { start: now - 30 * 86_400_000, end: now };
  }
  if (end < start) throw badRequest('periodEnd is before periodStart.');
  if (end - start > 400 * 86_400_000) {
    throw badRequest('Period is longer than the engine will assess in one snapshot.');
  }
  return { start, end };
}

const SUPPORTED_CURRENCIES = ['INR', 'USD', 'EUR', 'GBP', 'JPY', 'AED', 'SGD', 'CAD', 'AUD', 'CHF'];

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

module.exports = router;
