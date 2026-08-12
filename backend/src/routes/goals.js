const express = require('express');
const { pool } = require('../config/db');
const { requireFirebaseAuth, requireSameUser } = require('../middleware/auth');
const { ensureUserExists } = require('../services/users');

const router = express.Router();

/**
 * POST /goals/sync
 * Upserts a savings goal for the authenticated user.
 * Body: { uid, localId, type, targetAmount, targetDate, currentSaved, monthlyContribution }
 */
router.post('/sync', requireFirebaseAuth, requireSameUser, async (req, res, next) => {
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
        updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      ON DUPLICATE KEY UPDATE
        type                 = VALUES(type),
        target_amount        = VALUES(target_amount),
        target_date          = VALUES(target_date),
        current_saved        = VALUES(current_saved),
        monthly_contribution = VALUES(monthly_contribution),
        updated_at           = VALUES(updated_at)`,
      [
        req.user.uid,
        Number(goal.localId),
        goal.type,
        Number(goal.targetAmount),
        Number(goal.targetDate),
        Number(goal.currentSaved   ?? 0),
        Number(goal.monthlyContribution ?? 0),
        Date.now()
      ]
    );

    return res.status(204).send();
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
}

function createBadRequest(message) {
  const error = new Error(message);
  error.status = 400;
  return error;
}

module.exports = router;
