/**
 * Runs every hard constraint against a candidate and records what happened.
 *
 * Every constraint is evaluated, not just up to the first failure. Stopping
 * early would be faster and would make a trace lie by omission: "rejected
 * because the reserve is too thin" reads very differently from "rejected because
 * the reserve is too thin *and* there is no confirmed risk profile *and* the
 * income data is missing", and the second is what the user needs in order to fix
 * anything.
 *
 * A constraint that throws is treated as a rejection rather than being allowed
 * to take the request down. A bug in one rule must not become a recommendation
 * that skipped it — failing closed is the only safe direction here.
 */
const { HARD_CONSTRAINTS, constraintVersions } = require('./constraints');

/**
 * @returns {{ passed: boolean, verdicts: Array, failedIds: string[], notes: string[] }}
 */
function evaluateCandidate(candidate, context, constraints = HARD_CONSTRAINTS) {
  const verdicts = [];

  for (const constraint of constraints) {
    if (!constraint.appliesTo(candidate)) {
      verdicts.push({
        constraintId: constraint.id,
        version: constraint.version,
        applicable: false,
        passed: true,
        reason: null,
        detail: null
      });
      continue;
    }

    let outcome;
    try {
      outcome = constraint.evaluate(candidate, context);
    } catch (error) {
      // Fails closed. A rule that crashed has not been satisfied, and treating
      // it as passed would let a bug become an unchecked recommendation.
      outcome = {
        passed: false,
        reason: 'CONSTRAINT_ERROR',
        detail: `This rule could not be checked: ${error.message}`
      };
    }

    verdicts.push({
      constraintId: constraint.id,
      version: constraint.version,
      applicable: true,
      passed: outcome.passed,
      reason: outcome.reason,
      detail: outcome.detail
    });
  }

  const failed = verdicts.filter((verdict) => verdict.applicable && !verdict.passed);

  return {
    passed: failed.length === 0,
    verdicts,
    failedIds: failed.map((verdict) => verdict.constraintId),
    // Notes from constraints that passed but had something to say — a degraded
    // component, a reserve that survives but only just.
    notes: verdicts
      .filter((verdict) => verdict.applicable && verdict.passed && verdict.detail)
      .map((verdict) => verdict.detail),
    rejectionReasons: failed.map((verdict) => verdict.detail)
  };
}

/** Splits candidates into those that survive the gate and those that do not. */
function gate(candidates, context, constraints = HARD_CONSTRAINTS) {
  const eligible = [];
  const rejected = [];

  for (const candidate of candidates) {
    const result = evaluateCandidate(candidate, context, constraints);
    if (result.passed) {
      eligible.push({ ...candidate, constraintNotes: result.notes, verdicts: result.verdicts });
    } else {
      rejected.push({
        ...candidate,
        rejectedBy: result.failedIds,
        rejectionReasons: result.rejectionReasons,
        verdicts: result.verdicts
      });
    }
  }

  return { eligible, rejected, constraintVersions: constraintVersions() };
}

module.exports = { evaluateCandidate, gate, constraintVersions, HARD_CONSTRAINTS };
