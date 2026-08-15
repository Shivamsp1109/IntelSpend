const express = require('express');
const { pool } = require('../config/db');
const { requireFirebaseAuth, requireSameUser } = require('../middleware/auth');
const { syncLimiter } = require('../middleware/rateLimit');
const { pageParams, page } = require('../services/pagination');
const { ensureUserExists } = require('../services/users');
const { CATEGORIES } = require('../services/taxonomy');

const router = express.Router();

/**
 * POST /budgets/sync
 * Upserts a category budget for the authenticated user.
 * Body: { uid, localId, category, monthlyLimit, currency }
 *
 * The alert-tracking columns are deliberately absent. Whether a notification has
 * been shown is a fact about a handset rather than the account — someone with a
 * phone and a tablet should be warned on both — so that state stays local.
 */
router.post('/sync', requireFirebaseAuth, requireSameUser, syncLimiter, async (req, res, next) => {
  try {
    const budget = req.body;
    validateBudget(budget);

    await ensureUserExists(req.user.uid, req.user.email);

    await pool.execute(
      `INSERT INTO category_budgets (
        uid,
        local_id,
        category,
        monthly_limit,
        currency,
        updated_at
      ) VALUES (?, ?, ?, ?, ?, ?)
      ON DUPLICATE KEY UPDATE
        category      = VALUES(category),
        monthly_limit = VALUES(monthly_limit),
        currency      = VALUES(currency),
        updated_at    = VALUES(updated_at)`,
      [
        req.user.uid,
        Number(budget.localId),
        budget.category,
        Number(budget.monthlyLimit),
        budget.currency || 'INR',
        Date.now()
      ]
    );

    return res.status(204).send();
  } catch (error) {
    return next(error);
  }
});

/**
 * DELETE /budgets/sync/:localId
 * Removes a budget the user deleted on their device.
 */
router.delete('/sync/:localId', requireFirebaseAuth, syncLimiter, async (req, res, next) => {
  try {
    const localId = Number(req.params.localId);
    if (!Number.isFinite(localId)) throw createBadRequest('Invalid localId.');

    await pool.execute(
      'DELETE FROM category_budgets WHERE uid = ? AND local_id = ?',
      [req.user.uid, localId]
    );

    return res.status(204).send();
  } catch (error) {
    return next(error);
  }
});

/** GET /budgets — paginated read for restore. */
router.get('/', requireFirebaseAuth, syncLimiter, async (req, res, next) => {
  try {
    const { after, limit } = pageParams(req.query);

    const [rows] = await pool.execute(
      `SELECT local_id      AS localId,
              category,
              monthly_limit AS monthlyLimit,
              currency
         FROM category_budgets
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

function validateBudget(budget) {
  if (!budget) throw createBadRequest('Missing budget body.');
  if (!budget.uid) throw createBadRequest('Missing uid.');
  if (!Number.isFinite(Number(budget.localId))) throw createBadRequest('Invalid localId.');
  if (!budget.category || typeof budget.category !== 'string') {
    throw createBadRequest('Invalid category.');
  }
  // Checked against the shared vocabulary rather than accepted as free text: a
  // budget filed under a category the app does not know would never match any
  // spending, and would sit at zero forever with no visible reason.
  if (!CATEGORIES.includes(budget.category)) {
    throw createBadRequest(`Unknown category: ${budget.category}`);
  }
  if (!Number.isFinite(Number(budget.monthlyLimit)) || Number(budget.monthlyLimit) <= 0) {
    throw createBadRequest('Invalid monthlyLimit.');
  }
}

function createBadRequest(message) {
  const error = new Error(message);
  error.status = 400;
  return error;
}

module.exports = router;
