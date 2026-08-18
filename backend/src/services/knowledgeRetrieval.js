/**
 * Finding a reviewed, current, citable answer — or saying there isn't one.
 *
 * The refusal is the feature. A question about tax rules or regulatory
 * requirements has an answer that lives in a document, changes when that
 * document changes, and cannot be recalled reliably by a model. Answering from
 * pretrained knowledge produces the exact failure this layer exists to prevent:
 * a confident, unattributable, possibly-obsolete claim about somebody's legal
 * position.
 *
 * So retrieval returns stored text with a citation, or it returns nothing and
 * the assistant says it does not know. There is no third path.
 *
 * Three gates, and none of them is age.
 *
 * **Status** — only ACTIVE. A source marked SUPERSEDED has been replaced;
 * UNDER_REVIEW means nobody has confirmed it recently enough to stand behind.
 *
 * **Review date** — `today <= review_due_date`. A circular published last month
 * can be superseded next week, and a section of the Income Tax Act can be twenty
 * years old and perfectly current. Age tells you nothing; whether a person has
 * checked lately tells you what matters.
 *
 * **Effective window** — `today` within `[effective_from, effective_to]`. A rate
 * that applied to one assessment year must not answer a question about another,
 * and a document can be current while the passage quoted from it is not.
 */
const crypto = require('crypto');
const { pool } = require('../config/db');

const SOURCE_STATUS = Object.freeze({
  ACTIVE: 'ACTIVE',
  SUPERSEDED: 'SUPERSEDED',
  UNDER_REVIEW: 'UNDER_REVIEW'
});

/**
 * The topics this layer will answer on.
 *
 * Controlled rather than free text: matching on however somebody worded a topic
 * would make retrieval depend on the wording rather than the subject, and a
 * typo would silently return nothing.
 */
const TOPIC = Object.freeze({
  TAX_DEDUCTIONS: 'TAX_DEDUCTIONS',
  TAX_SLABS: 'TAX_SLABS',
  CAPITAL_GAINS: 'CAPITAL_GAINS',
  INVESTMENT_ADVICE_RULES: 'INVESTMENT_ADVICE_RULES',
  RISK_PROFILING: 'RISK_PROFILING',
  MUTUAL_FUND_RULES: 'MUTUAL_FUND_RULES',
  BANKING_RULES: 'BANKING_RULES',
  INSURANCE_RULES: 'INSURANCE_RULES',
  RETIREMENT_SCHEMES: 'RETIREMENT_SCHEMES'
});

/** Why nothing was returned, so the assistant can say which. */
const NO_ANSWER = Object.freeze({
  NOTHING_ON_FILE: 'NOTHING_ON_FILE',
  ALL_PAST_REVIEW: 'ALL_PAST_REVIEW',
  ALL_OUT_OF_EFFECT: 'ALL_OUT_OF_EFFECT',
  WRONG_JURISDICTION: 'WRONG_JURISDICTION'
});

/** The hash a source is checked against. Text only, normalised for whitespace. */
function hashSourceText(text) {
  return crypto.createHash('sha256')
    .update(String(text).replace(/\s+/g, ' ').trim())
    .digest('hex');
}

/**
 * Scores a snippet against the question's terms.
 *
 * Crude on purpose. This is a curated corpus of a few dozen entries, and
 * embedding search would be machinery without a problem to solve — a term either
 * appears in the keywords or it does not, and a topic match is worth more than
 * any number of keyword hits because the topic was assigned by the reviewer.
 */
function scoreSnippet(snippet, { topic, terms }) {
  let score = 0;
  if (topic && snippet.topic === topic) score += 10;

  const keywords = String(snippet.keywords || '').toLowerCase().split(/\s+/).filter(Boolean);
  for (const term of terms) {
    if (keywords.includes(term)) score += 2;
    else if (keywords.some((keyword) => keyword.startsWith(term) && term.length >= 4)) score += 1;
  }

  return score;
}

/** Question text reduced to matchable terms. */
function termsFrom(question) {
  return String(question ?? '')
    .toLowerCase()
    .replace(/[^a-z0-9\s]/g, ' ')
    .split(/\s+/)
    .filter((term) => term.length >= 3)
    .slice(0, 20);
}

/**
 * Loads candidates and applies every gate, reporting which one refused.
 *
 * Gates are applied in memory after a broad query rather than in SQL, so the
 * reason can be distinguished. "Nothing on this topic" and "everything on this
 * topic is past its review date" are different answers, and the second is the
 * one an operator needs to see.
 */
async function retrieve({ question, topic, jurisdiction = 'IN', now = Date.now(), limit = 3 }) {
  const [rows] = await pool.execute(
    `SELECT s.id            AS snippetId,
            s.topic,
            s.snippet_text  AS snippetText,
            s.keywords,
            s.effective_from AS effectiveFrom,
            s.effective_to   AS effectiveTo,
            k.id            AS sourceId,
            k.publisher,
            k.title,
            k.url,
            k.jurisdiction,
            k.reviewer,
            k.review_due_date AS reviewDueDate,
            k.effective_date  AS sourceEffectiveDate,
            k.status
       FROM knowledge_snippets s
       JOIN knowledge_sources k ON k.id = s.source_id
      WHERE (? IS NULL OR s.topic = ?)`,
    [topic ?? null, topic ?? null]
  );

  if (rows.length === 0) {
    return { found: false, reason: NO_ANSWER.NOTHING_ON_FILE, claims: [], snippets: [] };
  }

  const inJurisdiction = rows.filter((row) => row.jurisdiction === jurisdiction);
  if (inJurisdiction.length === 0) {
    return { found: false, reason: NO_ANSWER.WRONG_JURISDICTION, claims: [], snippets: [] };
  }

  const active = inJurisdiction.filter((row) => row.status === SOURCE_STATUS.ACTIVE);
  const reviewed = active.filter((row) => Number(row.reviewDueDate) >= now);
  if (reviewed.length === 0) {
    // Distinguished from "nothing on file" deliberately. A corpus that has all
    // gone past review is an operational problem, and reporting it as an absence
    // of knowledge would hide it.
    return { found: false, reason: NO_ANSWER.ALL_PAST_REVIEW, claims: [], snippets: [] };
  }

  const inEffect = reviewed.filter((row) =>
    Number(row.effectiveFrom) <= now &&
    (row.effectiveTo === null || Number(row.effectiveTo) > now));
  if (inEffect.length === 0) {
    return { found: false, reason: NO_ANSWER.ALL_OUT_OF_EFFECT, claims: [], snippets: [] };
  }

  const terms = termsFrom(question);
  const ranked = inEffect
    .map((row) => ({ row, score: scoreSnippet(row, { topic, terms }) }))
    .filter((entry) => entry.score > 0)
    .sort((a, b) => b.score - a.score)
    .slice(0, limit);

  if (ranked.length === 0) {
    return { found: false, reason: NO_ANSWER.NOTHING_ON_FILE, claims: [], snippets: [] };
  }

  const claims = await claimsFor(ranked.map((entry) => entry.row.snippetId));

  return {
    found: true,
    reason: null,
    snippets: ranked.map((entry) => ({
      snippetId: entry.row.snippetId,
      topic: entry.row.topic,
      text: entry.row.snippetText,
      score: entry.score,
      source: {
        sourceId: entry.row.sourceId,
        publisher: entry.row.publisher,
        title: entry.row.title,
        url: entry.row.url,
        reviewer: entry.row.reviewer,
        reviewDueDate: Number(entry.row.reviewDueDate)
      }
    })),
    claims
  };
}

/** The specific statements the matched snippets support. */
async function claimsFor(snippetIds) {
  if (snippetIds.length === 0) return [];

  const placeholders = snippetIds.map(() => '?').join(', ');
  const [rows] = await pool.execute(
    `SELECT c.claim_key  AS claimKey,
            c.claim_text AS claimText,
            c.snippet_id AS snippetId,
            k.publisher,
            k.title,
            k.url
       FROM knowledge_claims c
       JOIN knowledge_snippets s ON s.id = c.snippet_id
       JOIN knowledge_sources k  ON k.id = s.source_id
      WHERE c.snippet_id IN (${placeholders})`,
    snippetIds
  );

  return rows;
}

/**
 * Re-checks a source against the text now at its URL.
 *
 * A mismatch flips it to UNDER_REVIEW rather than updating the hash. Updating
 * would mean the app silently starts citing new text for an old claim, which is
 * precisely the failure the hash exists to catch — the document changed and
 * nobody read it.
 *
 * Takes the fetched text rather than fetching, so this is testable and so the
 * network call is somebody else's decision.
 */
async function verifySourceHash(sourceId, fetchedText, now = Date.now()) {
  const [rows] = await pool.execute(
    'SELECT id, source_hash AS sourceHash, status FROM knowledge_sources WHERE id = ?',
    [sourceId]
  );

  if (rows.length === 0) return { checked: false, reason: 'NO_SUCH_SOURCE' };

  const stored = rows[0].sourceHash;
  const current = hashSourceText(fetchedText);

  if (stored === current) {
    return { checked: true, changed: false, status: rows[0].status };
  }

  await pool.execute(
    `UPDATE knowledge_sources
        SET status = ?, updated_at = ?
      WHERE id = ?`,
    [SOURCE_STATUS.UNDER_REVIEW, now, sourceId]
  );

  return {
    checked: true,
    changed: true,
    status: SOURCE_STATUS.UNDER_REVIEW,
    detail:
      'The document at this URL no longer matches what was reviewed. It will ' +
      'not be cited until somebody checks it.'
  };
}

/**
 * What the assistant should say when there is nothing citable.
 *
 * Written here rather than left to the model, because this is the one place the
 * temptation to answer anyway is strongest and the cost of doing so is highest.
 */
function refusalFor(reason) {
  switch (reason) {
    case NO_ANSWER.ALL_PAST_REVIEW:
      return 'The app has material on this but it is overdue a check, so it is not being used.';
    case NO_ANSWER.ALL_OUT_OF_EFFECT:
      return 'What the app holds on this applied to an earlier period and is not current.';
    case NO_ANSWER.WRONG_JURISDICTION:
      return 'The app only holds Indian material, and this looks like it is about somewhere else.';
    default:
      return 'The app does not hold anything current on this.';
  }
}

module.exports = {
  retrieve,
  claimsFor,
  verifySourceHash,
  hashSourceText,
  scoreSnippet,
  termsFrom,
  refusalFor,
  SOURCE_STATUS,
  TOPIC,
  NO_ANSWER
};
