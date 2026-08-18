/**
 * Lets a model write the sentences without letting it write the numbers.
 *
 * The problem this solves is narrow and serious. A model explaining a financial
 * result will, sooner or later, state a figure that is close to the real one and
 * not it — a rounded surplus, a transposed digit, a plausible total it inferred
 * rather than read. On screen that is indistinguishable from a correct answer,
 * and it arrives with all the fluency of one.
 *
 * The obvious fix — ban digits from model output — was tried on paper and does
 * not survive contact: it breaks on dates, "three months", percentages, ordinals
 * and version numbers, none of which were ever the risk. Banning them either
 * cripples the prose or leaks through so many exceptions that the rule stops
 * meaning anything.
 *
 * So the model returns templates instead:
 *
 *   { template: 'You have {{cashFlow.surplus}} left over each month.',
 *     references: ['cashFlow.surplus'] }
 *
 * Every `{{reference}}` must resolve against a registry of values the engine
 * actually computed. The server substitutes the formatted figure. A reference
 * the registry does not know rejects the whole reply rather than rendering
 * partially — a paragraph with one unresolved slot is a paragraph nobody should
 * read, and silently dropping it would leave a sentence that means the opposite
 * of what it says.
 *
 * Ordinary language is untouched. "Over the next three months" passes through
 * because three months is not a financial figure this engine computed, and
 * pretending otherwise is how a guard becomes unusable.
 */
const money = require('./money');

/** Matches {{some.reference}} with optional surrounding whitespace. */
const REFERENCE_PATTERN = /\{\{\s*([a-zA-Z0-9_.]+)\s*\}\}/g;

/**
 * Currency-shaped literals a model wrote itself.
 *
 * The reference check alone is not enough, and it took a test to notice: a model
 * that writes "you have about 45,000 rupees spare" declares no references at
 * all, so there is nothing to validate and the sentence renders. The figure is
 * invented, reads as authoritative, and the whole mechanism has been bypassed by
 * a model that simply did not use it.
 *
 * A blanket digit ban was considered and rejected — it breaks on dates, "three
 * months", ordinals and version numbers, none of which were ever the risk. This
 * is narrower: only shapes that unambiguously claim an amount of money.
 *
 *   ₹45,000 · Rs. 45000 · 45,000 rupees · 45000 INR · 12,00,000
 *
 * A comma-grouped number counts on its own. Indian and Western grouping both
 * appear in amounts and neither appears in a date, an ordinal or a count of
 * months, so the separator is a reliable signal that money is being claimed.
 */
const CURRENCY_LITERAL_PATTERNS = Object.freeze([
  // A currency marker before the digits.
  /(?:₹|\bRs\.?|\bINR\b|\bUSD\b|\bEUR\b|\bGBP\b)\s*\d/i,
  // Digits before a currency word.
  /\d\s*(?:rupees?|lakhs?|crores?|\bINR\b|\bUSD\b|\bEUR\b|\bGBP\b|dollars?|pounds?|euros?)/i,
  // Comma-grouped digits, which no date or ordinal uses.
  /\d{1,3}(?:,\d{2,3})+/
]);

/** Whether a template claims an amount the engine did not supply. */
function currencyLiteralIn(template) {
  // Slots are removed first: a substituted value legitimately contains digits
  // and separators, and matching against the filled text would reject every
  // correct reply.
  const withoutSlots = template.replace(REFERENCE_PATTERN, ' ');

  for (const pattern of CURRENCY_LITERAL_PATTERNS) {
    const match = withoutSlots.match(pattern);
    if (match) return match[0].trim();
  }
  return null;
}

/** A reply longer than this is refused rather than truncated mid-figure. */
const MAX_PARAGRAPHS = 8;
const MAX_TEMPLATE_CHARS = 600;

/**
 * Builds the registry of figures a reply may refer to.
 *
 * Flat, dotted keys rather than a nested object: a reference is a single string
 * the model either got right or did not, and resolving a path through nested
 * data invites partial matches that half-work.
 *
 * Every value carries its formatted form, so the substitution is a lookup rather
 * than a formatting decision made at render time — the figure the user reads is
 * the one the engine produced, not a re-derivation of it.
 */
function buildValueRegistry(context) {
  const registry = new Map();

  const put = (key, value, formatted, description) => {
    if (value === null || value === undefined) return;
    registry.set(key, { key, value, formatted, description });
  };

  const putMoney = (key, amount, description) => {
    if (!amount) return;
    put(key, amount, money.toDecimalString(amount) + ' ' + amount.currency, description);
  };

  const putPercent = (key, fraction, description) => {
    if (fraction === null || fraction === undefined) return;
    put(key, fraction, `${Math.round(fraction * 100)}%`, description);
  };

  const { cashFlow, emergencyFund, debt, netWorth, goals, protection } = context;

  if (cashFlow) {
    putMoney('cashFlow.income', cashFlow.income?.baseline, 'Typical monthly income');
    putMoney('cashFlow.outflow', cashFlow.outflow?.baseline, 'Typical monthly spending');
    putMoney('cashFlow.surplus', cashFlow.surplus, 'Income less spending');
    putMoney('cashFlow.uncommittedSurplus', cashFlow.obligations?.uncommittedSurplus,
      'What is left after standing obligations');
    putMoney('cashFlow.monthlyObligations', cashFlow.obligations?.monthlyTotal,
      'What tracked commitments cost each month');
    putPercent('cashFlow.savingsRate', cashFlow.savingsRate, 'Share of income kept');
    put('cashFlow.monthsObserved', cashFlow.monthsObserved,
      String(cashFlow.monthsObserved), 'Complete months the baseline covers');
  }

  if (emergencyFund) {
    putMoney('reserve.eligible', emergencyFund.eligibleReserve, 'Savings reachable quickly');
    putMoney('reserve.required', emergencyFund.requiredReserve, 'What the target would need');
    putMoney('reserve.shortfall', emergencyFund.shortfall, 'How far short the reserve is');
    putMoney('reserve.essentialMonthlySpend', emergencyFund.essentialMonthlySpend,
      'Essential spending per month');
    if (emergencyFund.coverageMonths !== null && emergencyFund.coverageMonths !== undefined) {
      put('reserve.coverageMonths', emergencyFund.coverageMonths,
        emergencyFund.coverageMonths.toFixed(1), 'Months of essentials covered');
    }
    put('reserve.targetMonths', emergencyFund.target?.months,
      String(emergencyFund.target?.months), 'Months of cover suggested');
  }

  if (debt) {
    putMoney('debt.outstanding', debt.totalPrincipalOutstanding, 'Total still owed');
    putMoney('debt.monthlyService', debt.totalMonthlyService, 'Loan payments each month');
    putMoney('debt.remainingInterest', debt.totalRemainingInterest, 'Interest still to pay');
    putPercent('debt.serviceRatio', debt.debtServiceRatio, 'Loan payments as a share of income');
  }

  if (netWorth) {
    putMoney('netWorth.assets', netWorth.totalAssets, 'Total owned');
    putMoney('netWorth.liabilities', netWorth.totalLiabilities, 'Total owed');
    putMoney('netWorth.net', netWorth.netWorth, 'Owned less owed');
  }

  if (protection) {
    putMoney('protection.lifeCoverHeld', protection.life?.held, 'Life cover in force');
    putMoney('protection.lifeCoverShortfall', protection.life?.shortfall,
      'Gap against the conventional guideline');
  }

  // Per goal, keyed by local id. A model naming a goal that does not exist gets
  // an unknown reference and the reply is rejected, which is the intent.
  for (const goal of goals?.goals ?? []) {
    const prefix = `goal.${goal.localId}`;
    putMoney(`${prefix}.target`, goal.targetAtMaturity, 'What the goal will cost');
    putMoney(`${prefix}.saved`, goal.alreadySaved, 'Saved towards it so far');
    putMoney(`${prefix}.shortfall`, goal.shortfall, 'Still to find');
    putMoney(`${prefix}.required`, goal.requiredMonthlyContribution, 'Needed each month');
    putMoney(`${prefix}.committed`, goal.committedMonthlyContribution,
      'What the user committed to each month');
    put(`${prefix}.monthsRemaining`, goal.monthsRemaining,
      String(goal.monthsRemaining), 'Months until the target date');
  }

  return registry;
}

/**
 * Validates and fills one reply.
 *
 * Returns `{ ok: true, paragraphs }` or `{ ok: false, reason, unknownReferences }`.
 * Never returns partially-filled text: a template with one bad slot is rejected
 * whole, because the alternative is a sentence that reads fine and is wrong.
 */
function fillReply(reply, registry) {
  const paragraphs = Array.isArray(reply?.paragraphs) ? reply.paragraphs : null;

  if (!paragraphs || paragraphs.length === 0) {
    return { ok: false, reason: 'NO_PARAGRAPHS', unknownReferences: [] };
  }
  if (paragraphs.length > MAX_PARAGRAPHS) {
    return { ok: false, reason: 'TOO_MANY_PARAGRAPHS', unknownReferences: [] };
  }

  const unknown = [];
  const literals = [];
  const filled = [];

  for (const paragraph of paragraphs) {
    const template = typeof paragraph?.template === 'string' ? paragraph.template : null;

    if (!template || template.length === 0) {
      return { ok: false, reason: 'EMPTY_TEMPLATE', unknownReferences: [] };
    }
    if (template.length > MAX_TEMPLATE_CHARS) {
      return { ok: false, reason: 'TEMPLATE_TOO_LONG', unknownReferences: [] };
    }

    // Read from the template itself rather than the declared `references` list.
    // A model that under-declares would otherwise slip an unchecked slot past
    // the gate, and the list is the model's claim about its own output rather
    // than a fact about it.
    const used = [...template.matchAll(REFERENCE_PATTERN)].map((match) => match[1]);

    for (const reference of used) {
      if (!registry.has(reference)) unknown.push(reference);
    }

    // A figure the model wrote rather than referenced. Declaring no references
    // is not a way to avoid the check.
    const literal = currencyLiteralIn(template);
    if (literal) literals.push(literal);

    filled.push({
      text: template.replace(REFERENCE_PATTERN, (_, reference) =>
        registry.get(reference)?.formatted ?? `{{${reference}}}`),
      references: used
    });
  }

  if (unknown.length > 0) {
    return {
      ok: false,
      reason: 'UNKNOWN_REFERENCE',
      unknownReferences: [...new Set(unknown)],
      currencyLiterals: []
    };
  }

  if (literals.length > 0) {
    return {
      ok: false,
      reason: 'MODEL_AUTHORED_FIGURE',
      unknownReferences: [],
      currencyLiterals: [...new Set(literals)]
    };
  }

  return { ok: true, paragraphs: filled, unknownReferences: [], currencyLiterals: [] };
}

/**
 * The registry rendered for the prompt.
 *
 * Names and descriptions only — the values go in the user turn as structured
 * data, not interpolated into the instruction. Putting figures into the system
 * prompt is how a caveat string containing "ignore your instructions" stops
 * being data and starts being an instruction.
 */
function describeRegistry(registry) {
  return [...registry.values()]
    .map((entry) => `- {{${entry.key}}} — ${entry.description}`)
    .join('\n');
}

module.exports = {
  buildValueRegistry,
  fillReply,
  describeRegistry,
  currencyLiteralIn,
  REFERENCE_PATTERN,
  CURRENCY_LITERAL_PATTERNS,
  MAX_PARAGRAPHS,
  MAX_TEMPLATE_CHARS
};
