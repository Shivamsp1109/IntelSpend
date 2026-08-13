const express = require('express');
const { pool } = require('../config/db');
const { requireFirebaseAuth, requireSameUser } = require('../middleware/auth');
const { syncLimiter } = require('../middleware/rateLimit');
const { pageParams, page } = require('../services/pagination');

const router = express.Router();

/**
 * POST /incomes/sync
 * Upserts an income record for the authenticated user.
 * Body: { uid, localId, title, amount, currency, source, note?, date }
 */
router.post('/sync', requireFirebaseAuth, requireSameUser, syncLimiter, async (req, res, next) => {
  try {
    const income = req.body;
    validateIncome(income);

    await ensureUserExists(req.user.uid, req.user.email);

    await pool.execute(
      `INSERT INTO incomes (
        uid,
        local_id,
        title,
        amount,
        currency,
        source,
        note,
        income_date,
        reference,
        date_is_assumed,
        updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON DUPLICATE KEY UPDATE
        title       = VALUES(title),
        amount      = VALUES(amount),
        currency    = VALUES(currency),
        source      = VALUES(source),
        note        = VALUES(note),
        income_date = VALUES(income_date),
        reference   = VALUES(reference),
        date_is_assumed = VALUES(date_is_assumed),
        updated_at  = VALUES(updated_at)`,
      [
        req.user.uid,
        Number(income.localId),
        income.title,
        Number(income.amount),
        income.currency   || 'INR',
        income.source,
        income.note       ?? null,
        Number(income.date),
        income.reference  ?? null,
        income.dateIsAssumed ? 1 : 0,
        Date.now()
      ]
    );

    return res.status(204).send();
  } catch (error) {
    return next(error);
  }
});

/** The caller's own incomes, for rebuilding a device. See expenses GET /. */
router.get('/', requireFirebaseAuth, syncLimiter, async (req, res, next) => {
  try {
    const { after, limit } = pageParams(req.query);

    const [rows] = await pool.execute(
      `SELECT local_id AS localId,
              title,
              amount,
              currency,
              source,
              note,
              income_date AS date,
              reference,
              date_is_assumed
         FROM incomes
        WHERE uid = ? AND local_id > ?
        ORDER BY local_id ASC
        LIMIT ${limit}`,
      [req.user.uid, after]
    );

    // See the expenses route: TINYINT(1) arrives as a number, and the client
    // expects a JSON boolean.
    return res.json(page(
      rows.map(({ date_is_assumed: assumed, ...rest }) => (
        { ...rest, dateIsAssumed: assumed === 1 }
      )),
      limit
    ));
  } catch (error) {
    return next(error);
  }
});

function validateIncome(income) {
  if (!income) throw createBadRequest('Missing income body.');
  if (!income.uid) throw createBadRequest('Missing uid.');
  if (!Number.isFinite(Number(income.localId))) throw createBadRequest('Invalid localId.');
  if (!income.title || typeof income.title !== 'string') throw createBadRequest('Invalid title.');
  if (!Number.isFinite(Number(income.amount))) throw createBadRequest('Invalid amount.');
  if (!income.currency || typeof income.currency !== 'string') throw createBadRequest('Invalid currency.');
  if (!income.source || typeof income.source !== 'string') throw createBadRequest('Invalid source.');
  if (income.note !== undefined && income.note !== null && typeof income.note !== 'string')
    throw createBadRequest('Invalid note.');
  if (!Number.isFinite(Number(income.date))) throw createBadRequest('Invalid date.');
}

async function ensureUserExists(uid, email) {
  await pool.execute(
    `INSERT IGNORE INTO users (uid, name, email, providers, updated_at)
     VALUES (?, '', ?, CAST(? AS JSON), ?)`,
    [uid, email || '', JSON.stringify([]), Date.now()]
  );
}

function createBadRequest(message) {
  const error = new Error(message);
  error.status = 400;
  return error;
}

router.delete('/sync/:localId', requireFirebaseAuth, syncLimiter, async (req, res, next) => {
  try {
    const localId = req.params.localId;
    if (!Number.isFinite(Number(localId))) throw createBadRequest('Invalid localId.');
    await pool.execute(
      `DELETE FROM incomes WHERE uid = ? AND local_id = ?`,
      [req.user.uid, Number(localId)]
    );
    return res.status(204).send();
  } catch (error) {
    return next(error);
  }
});

module.exports = router;
