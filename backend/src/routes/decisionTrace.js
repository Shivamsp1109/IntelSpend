const express = require('express');
const { requireFirebaseAuth } = require('../middleware/auth');
const { syncLimiter } = require('../middleware/rateLimit');
const { readTrace, redactTrace } = require('../services/decisionTraceStore');

const router = express.Router();

/**
 * GET /decision-trace/:traceId
 *
 * Why a particular recommendation was made — the rules that ran, what they said,
 * what was rejected and what survived.
 *
 * Owner-scoped: a trace is read by matching both the id and the signed-in user,
 * so guessing a UUID gets a 404 rather than somebody else's finances. The
 * observed state is not returned here; the trace names a `snapshotId` and that
 * is fetched separately, so the sensitive data has one home rather than two.
 */
router.get('/:traceId', requireFirebaseAuth, syncLimiter, async (req, res, next) => {
  try {
    const trace = await readTrace(req.params.traceId, req.user.uid);

    if (!trace) {
      const error = new Error('No such decision trace.');
      error.status = 404;
      throw error;
    }

    return res.json(trace);
  } catch (error) {
    return next(error);
  }
});

/**
 * DELETE /decision-trace/:traceId
 *
 * Clears what a trace says about the user, keeping the record that it existed.
 *
 * Deliberately not a row deletion. A trace that vanishes leaves an audit trail
 * that cannot be relied on by anyone — including for demonstrating that a
 * redaction was honoured. What goes is the question, the payload and the consent
 * record; what stays is that a decision was made on a date, which is not itself
 * sensitive.
 *
 * Returns 204 whether or not it had already been redacted: the caller's intent
 * is satisfied either way, and erroring on a repeat would only invite a retry.
 */
router.delete('/:traceId', requireFirebaseAuth, syncLimiter, async (req, res, next) => {
  try {
    const existed = await redactTrace(req.params.traceId, req.user.uid);

    if (!existed) {
      const error = new Error('No such decision trace.');
      error.status = 404;
      throw error;
    }

    return res.status(204).send();
  } catch (error) {
    return next(error);
  }
});

module.exports = router;
