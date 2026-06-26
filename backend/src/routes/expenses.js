const express = require('express');
const { pool } = require('../config/db');
const { requireFirebaseAuth, requireSameUser } = require('../middleware/auth');

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
        updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?)
      ON DUPLICATE KEY UPDATE
        title = VALUES(title),
        amount = VALUES(amount),
        category = VALUES(category),
        expense_date = VALUES(expense_date),
        updated_at = VALUES(updated_at)`,
      [
        req.user.uid,
        Number(expense.localId),
        expense.title,
        Number(expense.amount),
        expense.category,
        Number(expense.date),
        Date.now()
      ]
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
  if (!Number.isFinite(Number(expense.amount))) throw createBadRequest('Invalid amount.');
  if (!expense.category || typeof expense.category !== 'string') throw createBadRequest('Invalid category.');
  if (!Number.isFinite(Number(expense.date))) throw createBadRequest('Invalid date.');
}

async function ensureUserExists(uid, email) {
  await pool.execute(
    `INSERT IGNORE INTO users (
      uid,
      name,
      email,
      providers,
      updated_at
    ) VALUES (?, '', ?, CAST(? AS JSON), ?)`,
    [uid, email || '', JSON.stringify([]), Date.now()]
  );
}

function createBadRequest(message) {
  const error = new Error(message);
  error.status = 400;
  return error;
}

module.exports = router;
