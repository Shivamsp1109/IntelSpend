const express = require('express');
const { pool } = require('../config/db');
const { requireFirebaseAuth, requireSameUser } = require('../middleware/auth');
const { ensureUserExists } = require('../services/users');

const router = express.Router();

router.post('/sync', requireFirebaseAuth, requireSameUser, async (req, res, next) => {
  try {
    const expense = req.body;
    validateExpense(expense);

    await ensureUserExists(req.user.uid, req.user.email);

    await pool.execute(
      `INSERT INTO expenses (
        uid,
        local_id,
        title,
        amount,
        category,
        expense_date,
        merchant,
        currency,
        source,
        updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON DUPLICATE KEY UPDATE
        title        = VALUES(title),
        amount       = VALUES(amount),
        category     = VALUES(category),
        expense_date = VALUES(expense_date),
        merchant     = VALUES(merchant),
        currency     = VALUES(currency),
        source       = VALUES(source),
        updated_at   = VALUES(updated_at)`,
      [
        req.user.uid,
        Number(expense.localId),
        expense.title,
        Number(expense.amount),
        expense.category,
        Number(expense.date),
        expense.merchant ?? null,
        expense.currency || 'INR',
        expense.source   || 'MANUAL',
        Date.now()
      ]
    );

    return res.status(204).send();
  } catch (error) {
    return next(error);
  }
});

router.delete('/sync/:localId', requireFirebaseAuth, async (req, res, next) => {
  try {
    const localId = req.params.localId;
    if (!Number.isFinite(Number(localId))) throw createBadRequest('Invalid localId.');

    await pool.execute(
      `DELETE FROM expenses WHERE uid = ? AND local_id = ?`,
      [req.user.uid, Number(localId)]
    );

    return res.status(204).send();
  } catch (error) {
    return next(error);
  }
});

function validateExpense(expense) {
  if (!expense) throw createBadRequest('Missing expense body.');
  if (!expense.uid) throw createBadRequest('Missing uid.');
  if (!Number.isFinite(Number(expense.localId))) throw createBadRequest('Invalid localId.');
  if (!expense.title || typeof expense.title !== 'string') throw createBadRequest('Invalid title.');
  if (!Number.isFinite(Number(expense.amount)) || Number(expense.amount) <= 0) throw createBadRequest('Invalid amount.');
  if (!expense.category || typeof expense.category !== 'string') throw createBadRequest('Invalid category.');
  if (!Number.isFinite(Number(expense.date))) throw createBadRequest('Invalid date.');
  // v2 optional fields — validated only when present
  if (expense.merchant !== undefined && expense.merchant !== null && typeof expense.merchant !== 'string')
    throw createBadRequest('Invalid merchant.');
  if (expense.currency !== undefined && typeof expense.currency !== 'string')
    throw createBadRequest('Invalid currency.');
  if (expense.source !== undefined && typeof expense.source !== 'string')
    throw createBadRequest('Invalid source.');
}

function createBadRequest(message) {
  const error = new Error(message);
  error.status = 400;
  return error;
}

module.exports = router;

