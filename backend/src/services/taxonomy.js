/**
 * The category and nature vocabularies, in one place.
 *
 * These must match the Android enums exactly. They previously did not: the
 * extraction schema offered "Education" and "Groceries" while the app knew
 * neither, so a correctly-classified grocery run arrived, matched nothing, and
 * was filed as "Other". The model was right and the answer was thrown away.
 *
 * Any label added here needs the matching entry in ExpenseCategory.kt, and the
 * app validates whatever comes back rather than trusting it — a mismatch should
 * degrade to Other, not crash, but it should also not happen.
 */

const CATEGORIES = [
  'Food & Dining',
  'Groceries',
  'Transport',
  'Fuel',
  'Travel',
  'Shopping',
  'Rent & Housing',
  'Utilities',
  'Mobile & Internet',
  'Subscriptions',
  'Entertainment',
  'Health & Medical',
  'Insurance',
  'Education',
  'Personal Care',
  'Home & Household',
  'Gifts & Donations',
  'Kids & Family',
  'Pets',
  'Taxes & Government',
  'Bank Fees & Charges',
  'Other'
];

/**
 * What kind of movement a debit represents.
 *
 * Separate from category because these are not spending at all, and the app
 * excludes them from every total. A transfer between someone's own accounts
 * filed as a category would still be summed into their month.
 */
const NATURES = [
  'Spending',
  'SelfTransfer',
  'LoanRepayment',
  'CreditCardPayment',
  'Investment',
  'Savings',
  'CashWithdrawal',
  'Refund',
  'Income'
];

module.exports = { CATEGORIES, NATURES };
