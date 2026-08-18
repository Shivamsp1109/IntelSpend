/**
 * Counting and costing model calls, per feature.
 *
 * Extracted so chat and extraction share the accounting without sharing a
 * budget. One combined cap fails in two directions — a year of statement imports
 * exhausting somebody's ability to ask a question, or a long conversation
 * blocking their next import — and both are the kind of limit a user
 * experiences as the app breaking rather than as a policy.
 */
const { pool } = require('../config/db');

const FEATURE = Object.freeze({
  EXTRACT: 'EXTRACT',
  CHAT: 'CHAT'
});

/**
 * Published per-million-token rates, by model.
 *
 * Approximate and worth saying so: these move, and the figure recorded is an
 * estimate for cost visibility rather than a billing record. The provider's
 * invoice is the truth.
 */
const COST_PER_MTOK = {
  'gemini-3.1-flash-lite': { input: 0.10, output: 0.40 },
  'gemini-3.1-flash': { input: 0.30, output: 2.50 },
  'gemini-3-pro': { input: 1.25, output: 10.00 }
};

const DEFAULT_INPUT_COST_PER_MTOK = 0.30;
const DEFAULT_OUTPUT_COST_PER_MTOK = 2.50;

function startOfMonthMillis(now = new Date()) {
  return Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1);
}

/** How many calls this user has made against one feature this month. */
async function countCallsThisMonth(uid, feature) {
  const [rows] = await pool.execute(
    'SELECT COUNT(*) AS calls FROM llm_usage WHERE uid = ? AND feature = ? AND created_at >= ?',
    [uid, feature, startOfMonthMillis()]
  );
  return Number(rows[0]?.calls || 0);
}

/** Records one call. Token counts only — never the content of a request. */
async function recordUsage({ uid, feature, model, usage }) {
  const inputTokens = usage?.inputTokens || 0;
  const outputTokens = usage?.outputTokens || 0;

  const rate = COST_PER_MTOK[model] || {
    input: DEFAULT_INPUT_COST_PER_MTOK,
    output: DEFAULT_OUTPUT_COST_PER_MTOK
  };
  const estimatedCostUsd =
    (inputTokens / 1_000_000) * rate.input +
    (outputTokens / 1_000_000) * rate.output;

  await pool.execute(
    `INSERT INTO llm_usage (uid, model, feature, input_tokens, output_tokens, estimated_cost_usd, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?)`,
    [uid, model, feature, inputTokens, outputTokens, estimatedCostUsd, Date.now()]
  );
}

module.exports = {
  countCallsThisMonth,
  recordUsage,
  startOfMonthMillis,
  FEATURE,
  COST_PER_MTOK
};
