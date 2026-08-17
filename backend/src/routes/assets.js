const express = require('express');
const { pool } = require('../config/db');
const { requireFirebaseAuth, requireSameUser } = require('../middleware/auth');
const { syncLimiter } = require('../middleware/rateLimit');
const { pageParams, page } = require('../services/pagination');
const { ensureUserExists } = require('../services/users');
const { LIQUIDITY, OWNERSHIP } = require('../engine/netWorthEngine');

const router = express.Router();

const ASSET_TYPES = [
  'CASH', 'BANK_ACCOUNT', 'FIXED_DEPOSIT', 'RECURRING_DEPOSIT',
  'MUTUAL_FUND', 'STOCK', 'BOND', 'ETF',
  'PROVIDENT_FUND', 'PENSION', 'NPS',
  'GOLD', 'REAL_ESTATE', 'VEHICLE',
  'INSURANCE_CASH_VALUE', 'CRYPTO', 'LOAN_GIVEN', 'OTHER'
];

const VERIFICATION_SOURCES = ['MANUAL', 'IMPORTED', 'CONFIRMED'];

/**
 * POST /assets/sync
 * Upserts one holding for the authenticated user.
 *
 * The value arrives as a decimal string rather than a number. A JSON number goes
 * through a double on the way in, so ₹1,99,999.99 can arrive a paisa light —
 * small enough never to be noticed and enough to make two reads of the same
 * holding disagree.
 */
router.post('/sync', requireFirebaseAuth, requireSameUser, syncLimiter, async (req, res, next) => {
  try {
    const asset = req.body;
    validateAsset(asset);

    await ensureUserExists(req.user.uid, req.user.email);

    await pool.execute(
      `INSERT INTO assets (
        uid, local_id, asset_type, label, current_value, currency, valuation_date,
        liquidity_class, lock_in_until, ownership, verification_source,
        account_type, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON DUPLICATE KEY UPDATE
        asset_type          = VALUES(asset_type),
        label               = VALUES(label),
        current_value       = VALUES(current_value),
        currency            = VALUES(currency),
        valuation_date      = VALUES(valuation_date),
        liquidity_class     = VALUES(liquidity_class),
        lock_in_until       = VALUES(lock_in_until),
        ownership           = VALUES(ownership),
        verification_source = VALUES(verification_source),
        account_type        = VALUES(account_type),
        updated_at          = VALUES(updated_at)`,
      [
        req.user.uid,
        Number(asset.localId),
        asset.assetType,
        asset.label,
        String(asset.currentValue),
        asset.currency || 'INR',
        Number(asset.valuationDate),
        asset.liquidityClass,
        asset.lockInUntil === null || asset.lockInUntil === undefined
          ? null
          : Number(asset.lockInUntil),
        asset.ownership || OWNERSHIP.SELF,
        asset.verificationSource || 'MANUAL',
        asset.accountType || null,
        Date.now()
      ]
    );

    return res.status(204).send();
  } catch (error) {
    return next(error);
  }
});

/** DELETE /assets/sync/:localId — removes a holding the user deleted. */
router.delete('/sync/:localId', requireFirebaseAuth, syncLimiter, async (req, res, next) => {
  try {
    const localId = Number(req.params.localId);
    if (!Number.isFinite(localId)) throw badRequest('Invalid localId.');

    await pool.execute('DELETE FROM assets WHERE uid = ? AND local_id = ?', [req.user.uid, localId]);
    return res.status(204).send();
  } catch (error) {
    return next(error);
  }
});

/** GET /assets — paginated read for restore. */
router.get('/', requireFirebaseAuth, syncLimiter, async (req, res, next) => {
  try {
    const { after, limit } = pageParams(req.query);

    const [rows] = await pool.execute(
      `SELECT local_id            AS localId,
              asset_type          AS assetType,
              label,
              current_value       AS currentValue,
              currency,
              valuation_date      AS valuationDate,
              liquidity_class     AS liquidityClass,
              lock_in_until       AS lockInUntil,
              ownership,
              verification_source AS verificationSource,
              account_type        AS accountType
         FROM assets
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

function validateAsset(asset) {
  if (!asset) throw badRequest('Missing asset body.');
  if (!asset.uid) throw badRequest('Missing uid.');
  if (!Number.isFinite(Number(asset.localId))) throw badRequest('Invalid localId.');
  if (!asset.label || typeof asset.label !== 'string') throw badRequest('Invalid label.');

  if (!ASSET_TYPES.includes(asset.assetType)) {
    throw badRequest(`Unknown assetType: ${asset.assetType}`);
  }

  // Checked rather than defaulted. Liquidity decides whether this counts as an
  // emergency reserve, and quietly filing an unrecognised value under the most
  // liquid class would count locked money as reachable.
  if (!Object.values(LIQUIDITY).includes(asset.liquidityClass)) {
    throw badRequest(`Unknown liquidityClass: ${asset.liquidityClass}`);
  }
  if (asset.ownership && !Object.values(OWNERSHIP).includes(asset.ownership)) {
    throw badRequest(`Unknown ownership: ${asset.ownership}`);
  }
  if (asset.verificationSource && !VERIFICATION_SOURCES.includes(asset.verificationSource)) {
    throw badRequest(`Unknown verificationSource: ${asset.verificationSource}`);
  }

  if (!/^-?\d+(\.\d{1,2})?$/.test(String(asset.currentValue))) {
    throw badRequest('currentValue must be a decimal string such as "1999.99".');
  }
  if (!Number.isFinite(Number(asset.valuationDate))) throw badRequest('Invalid valuationDate.');
}

function badRequest(message) {
  const error = new Error(message);
  error.status = 400;
  return error;
}

module.exports = router;
