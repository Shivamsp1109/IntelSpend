/**
 * Never suggest something that makes a monthly shortfall worse.
 *
 * Somebody already spending more than they earn does not need a savings plan
 * layered on top; they need the shortfall dealt with first. A recommendation
 * that adds a monthly commitment there is not merely unhelpful, it accelerates
 * the problem — and it is exactly what a naive optimiser produces, because a
 * goal-funding candidate scores well on every measure except the one that
 * matters.
 *
 * Candidates that reduce an outflow are exempt. Paying down a loan lowers the
 * monthly burden rather than raising it, and blocking that on the grounds of a
 * deficit would refuse the one kind of suggestion that helps.
 */
const money = require('../money');

module.exports = {
  id: 'NO_WORSENING_DEFICIT',
  version: '1.0.0',

  /** Only candidates that add to what has to be paid each month. */
  appliesTo: (candidate) => Boolean(candidate.addsMonthlyCommitment),

  evaluate(candidate, context) {
    const { cashFlow } = context;

    if (!cashFlow || cashFlow.monthsObserved === 0) {
      return {
        passed: false,
        reason: 'NO_BASELINE',
        detail:
          'There is no settled monthly surplus yet, so there is no way to tell ' +
          'whether this would fit. A few complete months of records would answer it.'
      };
    }

    const available = cashFlow.obligations.uncommittedSurplus;
    const monthly = candidate.monthlyAmount;

    // Checked before the amount, deliberately. A candidate that adds a monthly
    // commitment to somebody already underwater must be refused whether or not
    // the engine could work out how much it would cost — an unknown amount is
    // not a small one, and returning early on it would let exactly the wrong
    // suggestion through.
    if (money.isNegative(available)) {
      return {
        passed: false,
        reason: 'ALREADY_IN_DEFICIT',
        detail:
          'Your outgoings already exceed what comes in each month, so nothing ' +
          'that adds to them is suggested until that is settled.'
      };
    }

    if (!monthly || money.isZero(monthly)) {
      return { passed: true, reason: null, detail: null };
    }

    if (monthly.currency !== available.currency) {
      return {
        passed: false,
        reason: 'CURRENCY_MISMATCH',
        detail:
          `This is in ${monthly.currency} and your surplus is in ` +
          `${available.currency}; the two cannot be compared.`
      };
    }

    const after = money.subtract(available, monthly);
    if (money.isNegative(after)) {
      return {
        passed: false,
        reason: 'WOULD_CREATE_DEFICIT',
        detail:
          'This would commit more each month than you have left over, turning a ' +
          'surplus into a shortfall.'
      };
    }

    return {
      passed: true,
      reason: null,
      detail: 'Fits inside what is left over each month.'
    };
  }
};
