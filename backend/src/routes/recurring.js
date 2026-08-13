const express = require('express');
const { pool } = require('../config/db');
const { requireFirebaseAuth, requireSameUser } = require('../middleware/auth');
const { syncLimiter } = require('../middleware/rateLimit');
const { ensureUserExists } = require('../services/users');

const router = express.Router();

/**
 * POST /recurring/sync
 * Upserts a recurring entry for the authenticated user.
 * Body: { uid, localId, title, amount, cadence, type }
 */
router.post('/sync', requireFirebaseAuth, requireSameUser, syncLimiter, async (req, res, next) => {
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
        currency,
        nature,
        category,
        source,
        occurrence_count,
        confidence,
        updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON DUPLICATE KEY UPDATE
        title            = VALUES(title),
        amount           = VALUES(amount),
        cadence          = VALUES(cadence),
        type             = VALUES(type),
        currency         = VALUES(currency),
        nature           = VALUES(nature),
        category         = VALUES(category),
        source           = VALUES(source),
        occurrence_count = VALUES(occurrence_count),
        confidence       = VALUES(confidence),
        updated_at       = VALUES(updated_at)`,
      [
        req.user.uid,
        Number(entry.localId),
        entry.title,
        Number(entry.amount),
        entry.cadence,
        entry.type,
        // Defaulted rather than required, so an older client that does not send
        // these can still sync instead of having every request rejected.
        entry.currency || 'INR',
        entry.nature || 'Spending',
        entry.category || 'Other',
        entry.source || 'MANUAL',
        Number.isFinite(Number(entry.occurrenceCount)) ? Number(entry.occurrenceCount) : 0,
        clampConfidence(entry.confidence),
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
router.post('/link', requireFirebaseAuth, requireSameUser, syncLimiter, async (req, res, next) => {
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
router.delete('/link', requireFirebaseAuth, requireSameUser, syncLimiter, async (req, res, next) => {
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

/**
 * POST /recurring/dismissals
 * Records a detection the user rejected, so a reinstall does not resurrect it.
 * Body: { uid, signature, merchant, currency, nature, category, cadence,
 *         lastSeenAmount, lastSeenOccurrenceDate, dismissedAt }
 */
router.post('/dismissals', requireFirebaseAuth, requireSameUser, syncLimiter, async (req, res, next) => {
  try {
    const dismissal = req.body;
    if (!dismissal) throw createBadRequest('Missing dismissal body.');
    if (!dismissal.uid) throw createBadRequest('Missing uid.');
    if (!dismissal.signature || typeof dismissal.signature !== 'string') {
      throw createBadRequest('Invalid signature.');
    }
    if (!dismissal.cadence || typeof dismissal.cadence !== 'string') {
      throw createBadRequest('Invalid cadence.');
    }

    await ensureUserExists(req.user.uid, req.user.email);

    await pool.execute(
      `INSERT INTO dismissed_recurring_candidates (
        uid,
        signature,
        merchant,
        currency,
        nature,
        category,
        cadence,
        last_seen_amount,
        last_seen_occurrence_date,
        dismissed_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON DUPLICATE KEY UPDATE
        merchant                  = VALUES(merchant),
        cadence                   = VALUES(cadence),
        last_seen_amount          = VALUES(last_seen_amount),
        last_seen_occurrence_date = VALUES(last_seen_occurrence_date),
        dismissed_at              = VALUES(dismissed_at)`,
      [
        req.user.uid,
        dismissal.signature.slice(0, 512),
        String(dismissal.merchant || '').slice(0, 255),
        dismissal.currency || 'INR',
        dismissal.nature || 'Spending',
        dismissal.category || 'Other',
        dismissal.cadence,
        Number.isFinite(Number(dismissal.lastSeenAmount)) ? Number(dismissal.lastSeenAmount) : 0,
        Number.isFinite(Number(dismissal.lastSeenOccurrenceDate))
          ? Number(dismissal.lastSeenOccurrenceDate)
          : 0,
        Number.isFinite(Number(dismissal.dismissedAt)) ? Number(dismissal.dismissedAt) : Date.now()
      ]
    );

    return res.status(204).send();
  } catch (error) {
    return next(error);
  }
});

/** A score outside [0, 1] is meaningless; store something sane rather than refuse. */
function clampConfidence(value) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return 1;
  return Math.max(0, Math.min(1, parsed));
}

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
