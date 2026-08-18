/**
 * Never move money out of the emergency reserve into something volatile.
 *
 * The reserve exists to be there on the worst day. Any suggestion that spends it
 * — investing it, prepaying a loan with it, funding a goal from it — trades
 * away the one thing that makes the rest survivable, and does so at precisely
 * the moment the figures look most comfortable, because a healthy reserve is
 * what makes a portfolio recommendation look affordable in the first place.
 *
 * So this is a hard gate rather than a caution. A candidate that would take the
 * reserve below target is rejected, and one that would take it below the floor
 * is rejected outright regardless of what the target happens to be.
 *
 * The distinction between the two matters: someone deliberately running a
 * thinner buffer than this app suggests is making a choice, and blocking every
 * candidate on a target they have not agreed to would make the engine useless to
 * them. Falling below three months of essentials is not that — it is the point
 * at which an ordinary setback becomes a borrowing event.
 */
const money = require('../money');

/** Below this many months of essential spending, nothing may touch the reserve. */
const ABSOLUTE_FLOOR_MONTHS = 3;

module.exports = {
  id: 'PROTECT_EMERGENCY_RESERVE',
  version: '1.0.0',

  /** Only candidates that would actually spend liquid money. */
  appliesTo: (candidate) => Boolean(candidate.drawsFromReserve),

  evaluate(candidate, context) {
    const { emergencyFund } = context;

    if (!emergencyFund || emergencyFund.coverageMonths === null) {
      return {
        passed: false,
        reason: 'RESERVE_UNKNOWN',
        detail:
          'There is not enough recorded to say what your emergency reserve ' +
          'covers, so nothing that would spend it can be suggested.'
      };
    }

    const amount = candidate.amount;
    if (!amount || money.isZero(amount)) {
      return { passed: true, reason: null, detail: null };
    }

    if (amount.currency !== emergencyFund.currency) {
      return {
        passed: false,
        reason: 'CURRENCY_MISMATCH',
        detail:
          `This is in ${amount.currency} and your reserve is in ` +
          `${emergencyFund.currency}; the two cannot be compared.`
      };
    }

    const remaining = money.subtract(emergencyFund.eligibleReserve, amount);
    if (money.isNegative(remaining)) {
      return {
        passed: false,
        reason: 'EXCEEDS_RESERVE',
        detail: 'This would need more than you hold in reachable savings.'
      };
    }

    const essentials = emergencyFund.essentialMonthlySpend;
    if (!essentials || money.isZero(essentials)) {
      return {
        passed: false,
        reason: 'ESSENTIALS_UNKNOWN',
        detail:
          'Without a figure for your essential spending there is no way to ' +
          'tell what would be left, so this is not suggested.'
      };
    }

    const monthsAfter = remaining.minorUnits / essentials.minorUnits;

    if (monthsAfter < ABSOLUTE_FLOOR_MONTHS) {
      return {
        passed: false,
        reason: 'BELOW_ABSOLUTE_FLOOR',
        detail:
          `This would leave about ${monthsAfter.toFixed(1)} months of essential ` +
          `spending in reach, below the ${ABSOLUTE_FLOOR_MONTHS}-month floor at ` +
          'which an ordinary setback turns into borrowing.'
      };
    }

    if (monthsAfter < emergencyFund.target.months) {
      return {
        passed: false,
        reason: 'BELOW_TARGET',
        detail:
          `This would leave about ${monthsAfter.toFixed(1)} months of cover, ` +
          `below the ${emergencyFund.target.months} months suggested for your ` +
          'circumstances.'
      };
    }

    return {
      passed: true,
      reason: null,
      detail: `About ${monthsAfter.toFixed(1)} months of cover would remain.`
    };
  },

  ABSOLUTE_FLOOR_MONTHS
};
