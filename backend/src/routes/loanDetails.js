const express = require('express');
const { pool } = require('../config/db');
const { requireFirebaseAuth, requireSameUser } = require('../middleware/auth');
const { syncLimiter } = require('../middleware/rateLimit');
const { pageParams, page } = require('../services/pagination');
const { ensureUserExists } = require('../services/users');
const { RATE_TYPE, PREPAYMENT_CHARGE, PAYMENTS_PER_YEAR } = require('../engine/debtEngine');

const router = express.Router();

const COMPOUNDING = ['MONTHLY', 'ANNUAL', 'UNKNOWN'];

/**
 * POST /loan-details/sync
 * Upserts the terms behind one tracked commitment.
 *
 * Almost every field is optional, and that is the point. Someone who knows their
 * EMI and not their interest rate should still be able to record what they do
 * know — the engine's job is to say what it cannot work out from that, not to
 * demand a complete picture before it will say anything.
 */
router.post('/sync', requireFirebaseAuth, requireSameUser, syncLimiter, async (req, res, next) => {
  try {
    const loan = req.body;
    validateLoan(loan);

    await ensureUserExists(req.user.uid, req.user.email);

    await pool.execute(
      `INSERT INTO loan_details (
        uid, recurring_local_id, principal_outstanding, outstanding_as_of, currency,
        interest_rate, rate_type, rate_reset_date, interest_compounding,
        scheduled_payment, payment_frequency, remaining_installments, next_payment_date,
        prepayment_charge_type, prepayment_charge_value, fees_or_penalties, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON DUPLICATE KEY UPDATE
        principal_outstanding   = VALUES(principal_outstanding),
        outstanding_as_of       = VALUES(outstanding_as_of),
        currency                = VALUES(currency),
        interest_rate           = VALUES(interest_rate),
        rate_type               = VALUES(rate_type),
        rate_reset_date         = VALUES(rate_reset_date),
        interest_compounding    = VALUES(interest_compounding),
        scheduled_payment       = VALUES(scheduled_payment),
        payment_frequency       = VALUES(payment_frequency),
        remaining_installments  = VALUES(remaining_installments),
        next_payment_date       = VALUES(next_payment_date),
        prepayment_charge_type  = VALUES(prepayment_charge_type),
        prepayment_charge_value = VALUES(prepayment_charge_value),
        fees_or_penalties       = VALUES(fees_or_penalties),
        updated_at              = VALUES(updated_at)`,
      [
        req.user.uid,
        Number(loan.recurringLocalId),
        String(loan.principalOutstanding),
        Number(loan.outstandingAsOf),
        loan.currency || 'INR',
        optionalDecimal(loan.interestRate),
        loan.rateType || RATE_TYPE.UNKNOWN,
        optionalNumber(loan.rateResetDate),
        loan.interestCompounding || 'UNKNOWN',
        optionalDecimal(loan.scheduledPayment),
        loan.paymentFrequency || 'MONTHLY',
        optionalNumber(loan.remainingInstallments),
        optionalNumber(loan.nextPaymentDate),
        loan.prepaymentChargeType || PREPAYMENT_CHARGE.UNKNOWN,
        optionalDecimal(loan.prepaymentChargeValue),
        optionalDecimal(loan.feesOrPenalties),
        Date.now()
      ]
    );

    return res.status(204).send();
  } catch (error) {
    return next(error);
  }
});

/** DELETE /loan-details/sync/:recurringLocalId — the user removed the terms. */
router.delete('/sync/:recurringLocalId', requireFirebaseAuth, syncLimiter, async (req, res, next) => {
  try {
    const recurringLocalId = Number(req.params.recurringLocalId);
    if (!Number.isFinite(recurringLocalId)) throw badRequest('Invalid recurringLocalId.');

    await pool.execute(
      'DELETE FROM loan_details WHERE uid = ? AND recurring_local_id = ?',
      [req.user.uid, recurringLocalId]
    );
    return res.status(204).send();
  } catch (error) {
    return next(error);
  }
});

/** GET /loan-details — paginated read for restore. */
router.get('/', requireFirebaseAuth, syncLimiter, async (req, res, next) => {
  try {
    const { after, limit } = pageParams(req.query);

    const [rows] = await pool.execute(
      `SELECT recurring_local_id      AS recurringLocalId,
              principal_outstanding   AS principalOutstanding,
              outstanding_as_of       AS outstandingAsOf,
              currency,
              interest_rate           AS interestRate,
              rate_type               AS rateType,
              rate_reset_date         AS rateResetDate,
              interest_compounding    AS interestCompounding,
              scheduled_payment       AS scheduledPayment,
              payment_frequency       AS paymentFrequency,
              remaining_installments  AS remainingInstallments,
              next_payment_date       AS nextPaymentDate,
              prepayment_charge_type  AS prepaymentChargeType,
              prepayment_charge_value AS prepaymentChargeValue,
              fees_or_penalties       AS feesOrPenalties
         FROM loan_details
        WHERE uid = ? AND recurring_local_id > ?
        ORDER BY recurring_local_id ASC
        LIMIT ${limit}`,
      [req.user.uid, after]
    );

    return res.json(page(rows, limit, 'recurringLocalId'));
  } catch (error) {
    return next(error);
  }
});

const optionalNumber = (value) =>
  value === null || value === undefined || value === '' ? null : Number(value);

const optionalDecimal = (value) =>
  value === null || value === undefined || value === '' ? null : String(value);

function validateLoan(loan) {
  if (!loan) throw badRequest('Missing loan body.');
  if (!loan.uid) throw badRequest('Missing uid.');
  if (!Number.isFinite(Number(loan.recurringLocalId))) {
    throw badRequest('Invalid recurringLocalId.');
  }
  if (!/^-?\d+(\.\d{1,2})?$/.test(String(loan.principalOutstanding))) {
    throw badRequest('principalOutstanding must be a decimal string such as "500000.00".');
  }
  if (!Number.isFinite(Number(loan.outstandingAsOf))) throw badRequest('Invalid outstandingAsOf.');

  if (loan.rateType && !Object.values(RATE_TYPE).includes(loan.rateType)) {
    throw badRequest(`Unknown rateType: ${loan.rateType}`);
  }
  if (loan.interestCompounding && !COMPOUNDING.includes(loan.interestCompounding)) {
    throw badRequest(`Unknown interestCompounding: ${loan.interestCompounding}`);
  }
  if (loan.paymentFrequency && !PAYMENTS_PER_YEAR[loan.paymentFrequency]) {
    throw badRequest(`Unknown paymentFrequency: ${loan.paymentFrequency}`);
  }

  // Enumerated rather than free text, because a payoff comparison has to
  // subtract this. An unrecognised arrangement must land on UNKNOWN so the
  // engine declines to state a net benefit, rather than being coerced to NONE
  // and quietly reporting a saving that a fee would have wiped out.
  if (loan.prepaymentChargeType &&
      !Object.values(PREPAYMENT_CHARGE).includes(loan.prepaymentChargeType)) {
    throw badRequest(`Unknown prepaymentChargeType: ${loan.prepaymentChargeType}`);
  }
}

function badRequest(message) {
  const error = new Error(message);
  error.status = 400;
  return error;
}

module.exports = router;
