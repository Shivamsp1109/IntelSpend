const express = require('express');
const { pool } = require('../config/db');
const { requireFirebaseAuth } = require('../middleware/auth');
const { syncLimiter } = require('../middleware/rateLimit');
const { COMPONENT } = require('../engine/readiness');
const { buildAssessmentContext, presentable } = require('../services/assessmentContext');

const router = express.Router();

/**
 * GET /financial-state/snapshot
 *
 * Everything the engine sees for one user and period, stored and returned.
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
 *
 * The assessment itself is assembled by the shared context builder, which the
 * recommendations route also uses. Two endpoints computing the same figures
 * their own way is how an app starts giving different answers to the same
 * question depending on which screen asked.
 */
router.get('/snapshot', requireFirebaseAuth, syncLimiter, async (req, res, next) => {
  try {
    const context = await buildAssessmentContext({
      uid: req.user.uid,
      query: req.query,
      now: Date.now()
    });

    const readinessOf = (component) => context.dataQuality.readiness[component];

    return res.json({
      snapshotId: context.snapshotId,
      snapshotHash: context.snapshotHash,
      engineVersion: context.engineVersion,
      payloadSchemaVersion: context.payloadSchemaVersion,
      computedAt: context.now,
      period: { start: context.period.start, end: context.period.end },
      currency: context.currency,
      timezone: context.timezone,
      observedState: presentable(context.observedState),
      dataQuality: context.dataQuality,
      // Each assessment is carried with its readiness rather than as a bare
      // figure, so a consumer cannot present a baseline built on missing income
      // as though it stood on its own.
      cashFlow: {
        readiness: readinessOf(COMPONENT.CASH_FLOW),
        ...presentable(context.cashFlow)
      },
      debt: {
        readiness: readinessOf(COMPONENT.DEBT_SERVICE),
        ...presentable(context.debt)
      },
      netWorth: {
        readiness: readinessOf(COMPONENT.NET_WORTH),
        ...presentable(context.netWorth)
      },
      emergencyFund: presentable(context.emergencyFund),
      goals: {
        readiness: readinessOf(COMPONENT.GOAL_PROGRESS),
        ...presentable(context.goals)
      },
      protection: {
        readiness: readinessOf(COMPONENT.INSURANCE_GAP),
        ...presentable(context.protection)
      },
      portfolio: {
        readiness: readinessOf(COMPONENT.PORTFOLIO_ALIGNMENT),
        ...presentable(context.portfolio)
      },
      riskProfile: context.riskProfile,
      scenarios: presentable(context.scenarios),
      // Named so a reader can see what every projected figure rested on without
      // having to go and look it up.
      assumptionsInUse: context.assumptions.map((row) => ({
        id: row.id,
        scenarioType: row.scenario_type,
        assetClass: row.asset_class,
        timeHorizonBand: row.time_horizon_band,
        inflationPercent: row.inflation_rate === null ? null : Number(row.inflation_rate),
        expectedReturnPercent: row.expected_return === null ? null : Number(row.expected_return),
        source: row.source,
        version: row.version
      })),
      sourceWatermarks: context.watermarks
    });
  } catch (error) {
    return next(error);
  }
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

module.exports = router;
