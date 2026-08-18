const express = require('express');
const { pool } = require('../config/db');
const { requireFirebaseAuth, requireSameUser } = require('../middleware/auth');
const { syncLimiter } = require('../middleware/rateLimit');
const { pageParams, page } = require('../services/pagination');
const { ensureUserExists } = require('../services/users');
const { POLICY_TYPE } = require('../engine/insuranceEngine');

const router = express.Router();

const CADENCES = ['MONTHLY', 'QUARTERLY', 'HALF_YEARLY', 'YEARLY', 'SINGLE'];

/**
 * POST /insurance/sync
 *
 * `nomineeSet` is tri-state and stays that way: true, false, or absent. "No
 * nominee" is a real problem worth flagging and "we do not know" is not, so
 * coercing the second into the first would raise a warning about something
 * nobody has established.
 */
router.post('/sync', requireFirebaseAuth, requireSameUser, syncLimiter, async (req, res, next) => {
  try {
    const policy = req.body;
    validatePolicy(policy);

    await ensureUserExists(req.user.uid, req.user.email);

    await pool.execute(
      `INSERT INTO insurance_policies (
        uid, local_id, policy_type, provider, label, sum_assured, currency,
        premium_amount, premium_cadence, policy_end_date, nominee_set, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON DUPLICATE KEY UPDATE
        policy_type     = VALUES(policy_type),
        provider        = VALUES(provider),
        label           = VALUES(label),
        sum_assured     = VALUES(sum_assured),
        currency        = VALUES(currency),
        premium_amount  = VALUES(premium_amount),
        premium_cadence = VALUES(premium_cadence),
        policy_end_date = VALUES(policy_end_date),
        nominee_set     = VALUES(nominee_set),
        updated_at      = VALUES(updated_at)`,
      [
        req.user.uid,
        Number(policy.localId),
        policy.policyType,
        policy.provider || null,
        policy.label,
        String(policy.sumAssured),
        policy.currency || 'INR',
        optionalDecimal(policy.premiumAmount),
        policy.premiumCadence || 'YEARLY',
        optionalNumber(policy.policyEndDate),
        policy.nomineeSet === null || policy.nomineeSet === undefined
          ? null
          : (policy.nomineeSet ? 1 : 0),
        Date.now()
      ]
    );

    return res.status(204).send();
  } catch (error) {
    return next(error);
  }
});

router.delete('/sync/:localId', requireFirebaseAuth, syncLimiter, async (req, res, next) => {
  try {
    const localId = Number(req.params.localId);
    if (!Number.isFinite(localId)) throw badRequest('Invalid localId.');

    await pool.execute(
      'DELETE FROM insurance_policies WHERE uid = ? AND local_id = ?',
      [req.user.uid, localId]
    );
    return res.status(204).send();
  } catch (error) {
    return next(error);
  }
});

router.get('/', requireFirebaseAuth, syncLimiter, async (req, res, next) => {
  try {
    const { after, limit } = pageParams(req.query);

    const [rows] = await pool.execute(
      `SELECT local_id        AS localId,
              policy_type     AS policyType,
              provider,
              label,
              sum_assured     AS sumAssured,
              currency,
              premium_amount  AS premiumAmount,
              premium_cadence AS premiumCadence,
              policy_end_date AS policyEndDate,
              nominee_set     AS nomineeSet
         FROM insurance_policies
        WHERE uid = ? AND local_id > ?
        ORDER BY local_id ASC
        LIMIT ${limit}`,
      [req.user.uid, after]
    );

    return res.json(page(rows, limit));
  } catch (error) {
    return next(error);
  }
});

const optionalNumber = (value) =>
  value === null || value === undefined || value === '' ? null : Number(value);

const optionalDecimal = (value) =>
  value === null || value === undefined || value === '' ? null : String(value);

function validatePolicy(policy) {
  if (!policy) throw badRequest('Missing policy body.');
  if (!policy.uid) throw badRequest('Missing uid.');
  if (!Number.isFinite(Number(policy.localId))) throw badRequest('Invalid localId.');
  if (!policy.label || typeof policy.label !== 'string') throw badRequest('Invalid label.');

  if (!Object.values(POLICY_TYPE).includes(policy.policyType)) {
    throw badRequest(`Unknown policyType: ${policy.policyType}`);
  }
  if (policy.premiumCadence && !CADENCES.includes(policy.premiumCadence)) {
    throw badRequest(`Unknown premiumCadence: ${policy.premiumCadence}`);
  }
  if (!/^-?\d+(\.\d{1,2})?$/.test(String(policy.sumAssured))) {
    throw badRequest('sumAssured must be a decimal string such as "1000000.00".');
  }
}

function badRequest(message) {
  const error = new Error(message);
  error.status = 400;
  return error;
}

module.exports = router;
