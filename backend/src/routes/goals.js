const express = require('express');
const { pool } = require('../config/db');
const { requireFirebaseAuth, requireSameUser } = require('../middleware/auth');
const { syncLimiter } = require('../middleware/rateLimit');
const { pageParams, page } = require('../services/pagination');
const { ensureUserExists } = require('../services/users');
const {
  AMOUNT_BASIS, PRIORITY, FLEXIBILITY, GOAL_STATUS
} = require('../engine/goalEngine');

const router = express.Router();

/**
 * POST /goals/sync
 * Upserts a savings goal for the authenticated user.
 * Body: { uid, localId, type, targetAmount, targetDate, currentSaved, monthlyContribution }
 */
router.post('/sync', requireFirebaseAuth, requireSameUser, syncLimiter, async (req, res, next) => {
  try {
    const goal = req.body;
    validateGoal(goal);

    await ensureUserExists(req.user.uid, req.user.email);

    await pool.execute(
      `INSERT INTO goals (
        uid,
        local_id,
        type,
        target_amount,
        target_date,
        current_saved,
        monthly_contribution,
        currency,
        amount_basis,
        priority,
        flexibility,
        status,
        funding_source,
        updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON DUPLICATE KEY UPDATE
        type                 = VALUES(type),
        target_amount        = VALUES(target_amount),
        target_date          = VALUES(target_date),
        current_saved        = VALUES(current_saved),
        monthly_contribution = VALUES(monthly_contribution),
        currency             = VALUES(currency),
        amount_basis         = VALUES(amount_basis),
        priority             = VALUES(priority),
        flexibility          = VALUES(flexibility),
        status               = VALUES(status),
        funding_source       = VALUES(funding_source),
        updated_at           = VALUES(updated_at)`,
      [
        req.user.uid,
        Number(goal.localId),
        goal.type,
        Number(goal.targetAmount),
        Number(goal.targetDate),
        Number(goal.currentSaved   ?? 0),
        Number(goal.monthlyContribution ?? 0),
        goal.currency || 'INR',
        goal.amountBasis || AMOUNT_BASIS.TODAYS_MONEY,
        goal.priority || PRIORITY.IMPORTANT,
        goal.flexibility || FLEXIBILITY.BOTH_FLEXIBLE,
        goal.status || GOAL_STATUS.ACTIVE,
        goal.fundingSource || null,
        Date.now()
      ]
    );

    return res.status(204).send();
  } catch (error) {
    return next(error);
  }
});

/**
 * GET /goals — paginated read for restore.
 *
 * Goals had no read path at all until now, so a reinstall silently lost them
 * while expenses, incomes and commitments all came back. That was survivable
 * when a goal was four fields somebody could retype; it is not now that each one
 * also carries a basis, a priority and a flexibility they had to think about.
 */
router.get('/', requireFirebaseAuth, syncLimiter, async (req, res, next) => {
  try {
    const { after, limit } = pageParams(req.query);

    const [rows] = await pool.execute(
      `SELECT local_id             AS localId,
              type,
              target_amount        AS targetAmount,
              target_date          AS targetDate,
              current_saved        AS currentSaved,
              monthly_contribution AS monthlyContribution,
              currency,
              amount_basis         AS amountBasis,
              priority,
              flexibility,
              status,
              funding_source       AS fundingSource
         FROM goals
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

function validateGoal(goal) {
  if (!goal) throw createBadRequest('Missing goal body.');
  if (!goal.uid) throw createBadRequest('Missing uid.');
  if (!Number.isFinite(Number(goal.localId))) throw createBadRequest('Invalid localId.');
  if (!goal.type || typeof goal.type !== 'string') throw createBadRequest('Invalid type.');
  if (!Number.isFinite(Number(goal.targetAmount)) || Number(goal.targetAmount) <= 0) throw createBadRequest('Invalid targetAmount.');
  if (!Number.isFinite(Number(goal.targetDate))) throw createBadRequest('Invalid targetDate.');

  // Checked rather than defaulted. `amountBasis` decides whether the target gets
  // inflated at all, so an unrecognised value silently becoming "today's money"
  // would grow a figure the user had already stated in future terms.
  const enums = [
    ['amountBasis', AMOUNT_BASIS],
    ['priority', PRIORITY],
    ['flexibility', FLEXIBILITY],
    ['status', GOAL_STATUS]
  ];
  for (const [field, vocabulary] of enums) {
    const value = goal[field];
    if (value && !Object.values(vocabulary).includes(value)) {
      throw createBadRequest(`Unknown ${field}: ${value}`);
    }
  }
}

function createBadRequest(message) {
  const error = new Error(message);
  error.status = 400;
  return error;
}

module.exports = router;
