/**
 * Vocabulary shared across the engine.
 *
 * Kept in one place because these strings end up in stored snapshots and
 * decision traces, where a value that drifts between modules stops matching
 * history that has already been written.
 */

/**
 * Bumped when a formula changes what it produces from the same inputs.
 *
 * Recorded on every snapshot so a figure computed last month can be told apart
 * from the same figure computed today under different arithmetic — otherwise a
 * corrected formula silently makes past assessments look like present ones.
 */
const ENGINE_VERSION = '1.0.0';

/**
 * Bumped when the shape of a stored payload changes.
 *
 * Separate from ENGINE_VERSION: a new domain appearing in observed state changes
 * the shape without changing any arithmetic, and a reader needs to know which it
 * is dealing with.
 */
const PAYLOAD_SCHEMA_VERSION = 1;

/**
 * How money left the account, mirroring the app's TransactionNature.
 *
 * Only Spending is consumption. A statement import picks up transfers between
 * the user's own accounts, card bill payments, EMI and ATM withdrawals as debits
 * alongside real purchases, and counting them makes a month look ruinous.
 */
const NATURE = Object.freeze({
  SPENDING: 'Spending',
  SELF_TRANSFER: 'SelfTransfer',
  LOAN_REPAYMENT: 'LoanRepayment',
  CREDIT_CARD_PAYMENT: 'CreditCardPayment',
  INVESTMENT: 'Investment',
  SAVINGS: 'Savings',
  CASH_WITHDRAWAL: 'CashWithdrawal',
  REFUND: 'Refund',
  INCOME: 'Income'
});

/** Natures that represent money genuinely leaving, each counted separately. */
const OUTFLOW_NATURES = Object.freeze([
  NATURE.SPENDING,
  NATURE.LOAN_REPAYMENT,
  NATURE.INVESTMENT,
  NATURE.SAVINGS
]);

/** A commitment the user has confirmed and not paused or ended. */
const RECURRING_STATUS = Object.freeze({
  ACTIVE: 'ACTIVE',
  PAUSED: 'PAUSED',
  ENDED: 'ENDED'
});

const CADENCE = Object.freeze({
  DAILY: 'DAILY',
  WEEKLY: 'WEEKLY',
  BIWEEKLY: 'BIWEEKLY',
  MONTHLY: 'MONTHLY',
  QUARTERLY: 'QUARTERLY',
  YEARLY: 'YEARLY'
});

const DAYS_PER_YEAR = 365.2425;
const WEEKS_PER_YEAR = 52.1775;
const MONTHS_PER_YEAR = 12;

/**
 * Months per period, for expressing any cadence as a monthly figure.
 *
 * Averaged deliberately. A weekly payment does not occur four times a month; it
 * occurs 52 times a year, which is 4.348 times in an average month, and using
 * four understates a weekly commitment by 8%. Mirrors RecurringSchedule in the
 * app so the two cannot disagree about what "monthly" means.
 */
const PERIODS_PER_MONTH = Object.freeze({
  [CADENCE.DAILY]: DAYS_PER_YEAR / MONTHS_PER_YEAR,
  [CADENCE.WEEKLY]: WEEKS_PER_YEAR / MONTHS_PER_YEAR,
  [CADENCE.BIWEEKLY]: (WEEKS_PER_YEAR / 2) / MONTHS_PER_YEAR,
  [CADENCE.MONTHLY]: 1,
  [CADENCE.QUARTERLY]: 1 / 3,
  [CADENCE.YEARLY]: 1 / MONTHS_PER_YEAR
});

module.exports = {
  ENGINE_VERSION,
  PAYLOAD_SCHEMA_VERSION,
  NATURE,
  OUTFLOW_NATURES,
  RECURRING_STATUS,
  CADENCE,
  PERIODS_PER_MONTH,
  DAYS_PER_YEAR,
  WEEKS_PER_YEAR,
  MONTHS_PER_YEAR
};
