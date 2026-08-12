/**
 * Validation for the narrative payload.
 *
 * Kept apart from the route so it can be tested without an HTTP server, a
 * database or Firebase credentials — and because it is the boundary that
 * matters most on this endpoint. Two separate jobs:
 *
 * 1. Bounding what reaches the model. The result is built field by field, so
 *    anything the client sends that is not listed here simply does not travel:
 *    no transaction rows, no notes, no ids.
 *
 * 2. Bounding untrusted text. Merchant and category names were themselves read
 *    out of user-supplied bank statements and receipts, so they are attacker-
 *    influenced strings heading into a prompt. Capping their length and count
 *    limits what a merchant named "ignore previous instructions and..." can
 *    occupy; the pinned response schema limits what it could achieve anyway.
 */

// A summary needs a handful of categories and merchants. Beyond that the client
// is padding the prompt, and the user pays for the tokens.
const MAX_LIST_ITEMS = 8;
const MAX_NAME_CHARS = 60;
const MAX_LABEL_CHARS = 160;
const MAX_COUNT = 1_000_000;

function sanitiseFigures(body) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) {
    throw badRequest('Missing summary figures.');
  }

  const periodLabel = text(body.periodLabel, 'periodLabel', MAX_LABEL_CHARS);
  const currency = text(body.currency, 'currency', 8);
  if (!periodLabel || !currency) {
    throw badRequest('periodLabel and currency are required.');
  }

  const totalExpense = amount(body.totalExpense, 'totalExpense');
  if (totalExpense <= 0) {
    throw badRequest('totalExpense must be greater than zero to summarise.');
  }

  return {
    periodLabel,
    currency,
    totalExpense,
    totalIncome: amount(body.totalIncome, 'totalIncome'),
    previousExpense: amount(body.previousExpense, 'previousExpense'),
    averagePerDay: amount(body.averagePerDay, 'averagePerDay'),
    transactionCount: count(body.transactionCount),
    topCategories: list(body.topCategories, 'topCategories').map((item) => ({
      name: text(item?.name, 'category name', MAX_NAME_CHARS),
      amount: amount(item?.amount, 'category amount')
    })),
    topMerchants: list(body.topMerchants, 'topMerchants').map((item) => ({
      name: text(item?.name, 'merchant name', MAX_NAME_CHARS),
      amount: amount(item?.amount, 'merchant amount'),
      count: count(item?.count)
    })),
    // The rule-based findings the app already computed, so the model describes
    // conclusions the app stands behind rather than drawing its own.
    highlights: list(body.highlights, 'highlights')
      .map((item) => text(item, 'highlight', MAX_LABEL_CHARS))
      .filter(Boolean)
  };
}

function text(value, field, maxLength) {
  if (value === undefined || value === null) return '';
  if (typeof value !== 'string') throw badRequest(`${field} must be a string.`);
  return value.trim().slice(0, maxLength);
}

function amount(value, field) {
  if (value === undefined || value === null) return 0;
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < 0) {
    throw badRequest(`${field} must be a non-negative number.`);
  }
  return Math.round(parsed * 100) / 100;
}

function count(value) {
  const parsed = Math.trunc(Number(value) || 0);
  return Math.min(Math.max(parsed, 0), MAX_COUNT);
}

function list(value, field) {
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value)) throw badRequest(`${field} must be an array.`);
  if (value.length > MAX_LIST_ITEMS) {
    throw badRequest(`${field} may hold at most ${MAX_LIST_ITEMS} items.`);
  }
  return value;
}

function badRequest(message) {
  const error = new Error(message);
  error.status = 400;
  return error;
}

module.exports = {
  sanitiseFigures,
  MAX_LIST_ITEMS,
  MAX_NAME_CHARS,
  MAX_LABEL_CHARS
};
