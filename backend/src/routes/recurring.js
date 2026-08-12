const express = require('express');
const { pool } = require('../config/db');
const { requireFirebaseAuth, requireSameUser } = require('../middleware/auth');
const { ensureUserExists } = require('../services/users');

const router = express.Router();

/**
 * POST /recurring/sync
 * Upserts a recurring entry for the authenticated user.
 * Body: { uid, localId, title, amount, cadence, type }
 */
router.post('/sync', requireFirebaseAuth, requireSameUser, async (req, res, next) => {
  try {
    const entry = req.body;
    validateRecurring(entry);

    await ensureUserExists(req.user.uid, req.user.email);

    await pool.execute(
      `INSERT INTO recurring (
        uid,
        local_id,
        title,
        amount,
        cadence,
        type,
        updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?)
      ON DUPLICATE KEY UPDATE
        title      = VALUES(title),
        amount     = VALUES(amount),
        cadence    = VALUES(cadence),
        type       = VALUES(type),
        updated_at = VALUES(updated_at)`,
      [
        req.user.uid,
        Number(entry.localId),
        entry.title,
        Number(entry.amount),
        entry.cadence,
        entry.type,
        Date.now()
      ]
    );

    return res.status(204).send();
  } catch (error) {
    return next(error);
  }
});

/**
 * POST /recurring/link
 * Links an expense to a recurring entry (upserts the cross-ref row).
 * Body: { uid, recurringLocalId, expenseLocalId }
 */
router.post('/link', requireFirebaseAuth, requireSameUser, async (req, res, next) => {
  try {
    const { uid, recurringLocalId, expenseLocalId } = req.body;
    if (!uid) throw createBadRequest('Missing uid.');
    if (!Number.isFinite(Number(recurringLocalId))) throw createBadRequest('Invalid recurringLocalId.');
    if (!Number.isFinite(Number(expenseLocalId))) throw createBadRequest('Invalid expenseLocalId.');

    await pool.execute(
      `INSERT IGNORE INTO recurring_expense_cross_ref
         (uid, recurring_local_id, expense_local_id)
       VALUES (?, ?, ?)`,
      [req.user.uid, Number(recurringLocalId), Number(expenseLocalId)]
    );

    return res.status(204).send();
  } catch (error) {
    return next(error);
  }
});

/**
 * DELETE /recurring/link
 * Removes a cross-ref row (unlinks an expense from a recurring entry).
 * Body: { uid, recurringLocalId, expenseLocalId }
 */
router.delete('/link', requireFirebaseAuth, requireSameUser, async (req, res, next) => {
  try {
    const { uid, recurringLocalId, expenseLocalId } = req.body;
    if (!uid) throw createBadRequest('Missing uid.');
    if (!Number.isFinite(Number(recurringLocalId))) throw createBadRequest('Invalid recurringLocalId.');
    if (!Number.isFinite(Number(expenseLocalId))) throw createBadRequest('Invalid expenseLocalId.');

    await pool.execute(
      `DELETE FROM recurring_expense_cross_ref
       WHERE uid = ? AND recurring_local_id = ? AND expense_local_id = ?`,
      [req.user.uid, Number(recurringLocalId), Number(expenseLocalId)]
    );

    return res.status(204).send();
  } catch (error) {
    return next(error);
  }
});

function validateRecurring(entry) {
  if (!entry) throw createBadRequest('Missing recurring body.');
  if (!entry.uid) throw createBadRequest('Missing uid.');
  if (!Number.isFinite(Number(entry.localId))) throw createBadRequest('Invalid localId.');
  if (!entry.title || typeof entry.title !== 'string') throw createBadRequest('Invalid title.');
  if (!Number.isFinite(Number(entry.amount)) || Number(entry.amount) <= 0) throw createBadRequest('Invalid amount.');
  if (!entry.cadence || typeof entry.cadence !== 'string') throw createBadRequest('Invalid cadence.');
  if (!entry.type || typeof entry.type !== 'string') throw createBadRequest('Invalid type.');
}

function createBadRequest(message) {
  const error = new Error(message);
  error.status = 400;
  return error;
}

module.exports = router;
