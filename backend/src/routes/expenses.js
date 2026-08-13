const express = require('express');
const { pool } = require('../config/db');
const { requireFirebaseAuth, requireSameUser } = require('../middleware/auth');
const { syncLimiter } = require('../middleware/rateLimit');
const { pageParams, page } = require('../services/pagination');
const { ensureUserExists } = require('../services/users');

const router = express.Router();

router.post('/sync', requireFirebaseAuth, requireSameUser, syncLimiter, async (req, res, next) => {
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
        reference,
        date_is_assumed,
        updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON DUPLICATE KEY UPDATE
        title        = VALUES(title),
        amount       = VALUES(amount),
        category     = VALUES(category),
        expense_date = VALUES(expense_date),
        merchant     = VALUES(merchant),
        currency     = VALUES(currency),
        source       = VALUES(source),
        reference    = VALUES(reference),
        date_is_assumed = VALUES(date_is_assumed),
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
        expense.reference ?? null,
        expense.dateIsAssumed ? 1 : 0,
        Date.now()
      ]
    );

    return res.status(204).send();
  } catch (error) {
    return next(error);
  }
});

/**
 * The caller's own expenses, oldest local_id first, for rebuilding a device.
 *
 * Scoped to req.user.uid from the verified token — never to anything the
 * client sends — so no request can read another account's transactions.
 */
router.get('/', requireFirebaseAuth, syncLimiter, async (req, res, next) => {
  try {
    const { after, limit } = pageParams(req.query);

    const [rows] = await pool.execute(
      `SELECT local_id AS localId,
              title,
              amount,
              category,
              expense_date AS date,
              merchant,
              currency,
              source,
              reference,
              date_is_assumed
         FROM expenses
        WHERE uid = ? AND local_id > ?
        ORDER BY local_id ASC
        LIMIT ${limit}`,
      [req.user.uid, after]
    );

    return res.json(page(rows.map(toClientShape), limit));
  } catch (error) {
    return next(error);
  }
});

/**
 * MySQL returns TINYINT(1) as a number, and the client deserialises this field
 * as a boolean — a JSON `1` where `true` is expected fails outright rather than
 * degrading. Converted here rather than in SQL so it does not depend on driver
 * type-casting behaviour.
 */
function toClientShape(row) {
  const { date_is_assumed: dateIsAssumed, ...rest } = row;
  return { ...rest, dateIsAssumed: dateIsAssumed === 1 };
}

router.delete('/sync/:localId', requireFirebaseAuth, syncLimiter, async (req, res, next) => {
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

