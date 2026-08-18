/**
 * Loading reviewed knowledge into the database.
 *
 * The corpus ships empty, and that is a decision rather than an omission.
 *
 * Every entry here becomes something the app states to a user as a regulatory or
 * tax fact, with a publisher's name attached. Generating that text from a
 * model's recollection would produce authoritative-looking claims about
 * somebody's legal position that no document actually supports — which is the
 * precise failure this entire layer was built to prevent. Building the machinery
 * and then filling it with invented content would be worse than not building it,
 * because the citation makes the claim more credible, not less.
 *
 * So content arrives through here: fetched from the publisher, read by a named
 * person, and loaded with that person's name on it. Validation below refuses
 * anything that skips a step.
 *
 * See KNOWLEDGE_SEEDING.md for the workflow.
 */
const { pool } = require('../config/db');
const { hashSourceText, SOURCE_STATUS, TOPIC } = require('./knowledgeRetrieval');

/** A review that never expires is not a review. */
const MAX_REVIEW_INTERVAL_DAYS = 365;

/**
 * Checks one entry before it can be loaded.
 *
 * Strict, and deliberately unhelpful about it: a knowledge file that almost
 * validates should fail, because the cost of a bad entry is a wrong claim shown
 * to somebody with a regulator's name beside it.
 */
function validateEntry(entry, now = Date.now()) {
  const problems = [];

  const required = ['publisher', 'title', 'url', 'reviewer', 'sourceText'];
  for (const field of required) {
    if (!entry?.[field] || String(entry[field]).trim().length === 0) {
      problems.push(`Missing ${field}.`);
    }
  }

  if (entry?.url && !/^https:\/\//i.test(entry.url)) {
    // Not pedantry: a claim sourced over plain HTTP cannot be shown to have come
    // from the publisher it names.
    problems.push('url must be https.');
  }

  if (!Number.isFinite(Number(entry?.reviewDueDate))) {
    problems.push('Missing reviewDueDate.');
  } else {
    const due = Number(entry.reviewDueDate);
    if (due <= now) problems.push('reviewDueDate is already past.');
    if (due > now + MAX_REVIEW_INTERVAL_DAYS * 86_400_000) {
      problems.push(`reviewDueDate is more than ${MAX_REVIEW_INTERVAL_DAYS} days out.`);
    }
  }

  const snippets = Array.isArray(entry?.snippets) ? entry.snippets : [];
  if (snippets.length === 0) problems.push('At least one snippet is required.');

  snippets.forEach((snippet, index) => {
    if (!Object.values(TOPIC).includes(snippet?.topic)) {
      problems.push(`snippets[${index}].topic is not a known topic.`);
    }
    if (!snippet?.text || String(snippet.text).trim().length < 20) {
      problems.push(`snippets[${index}].text is missing or too short to be a quotation.`);
    }
    if (!Number.isFinite(Number(snippet?.effectiveFrom))) {
      problems.push(`snippets[${index}].effectiveFrom is missing.`);
    }

    const claims = Array.isArray(snippet?.claims) ? snippet.claims : [];
    if (claims.length === 0) {
      // A snippet supporting nothing is a quotation nobody can check against a
      // statement, which is the thing claims exist to make possible.
      problems.push(`snippets[${index}] has no claims.`);
    }
    claims.forEach((claim, claimIndex) => {
      if (!claim?.key || !/^[a-z0-9_.]+$/.test(claim.key)) {
        problems.push(`snippets[${index}].claims[${claimIndex}].key must be a lowercase handle.`);
      }
      if (!claim?.text || String(claim.text).trim().length < 10) {
        problems.push(`snippets[${index}].claims[${claimIndex}].text is missing or too short.`);
      }
    });
  });

  return problems;
}

/**
 * Loads one validated entry.
 *
 * Inserted as ACTIVE only because a reviewer is named and a review date is set —
 * both enforced above. The default in the schema is UNDER_REVIEW, so a row that
 * somehow arrives without going through here is inert until somebody promotes it.
 */
async function loadEntry(entry, now = Date.now()) {
  const problems = validateEntry(entry, now);
  if (problems.length > 0) {
    const error = new Error(`Knowledge entry rejected:\n- ${problems.join('\n- ')}`);
    error.status = 400;
    throw error;
  }

  const [result] = await pool.execute(
    `INSERT INTO knowledge_sources (
      publisher, title, url, jurisdiction, reviewer, retrieved_date,
      effective_date, review_due_date, source_hash, status, updated_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    [
      entry.publisher,
      entry.title,
      entry.url,
      entry.jurisdiction || 'IN',
      entry.reviewer,
      Number(entry.retrievedDate) || now,
      entry.effectiveDate === undefined ? null : Number(entry.effectiveDate),
      Number(entry.reviewDueDate),
      hashSourceText(entry.sourceText),
      SOURCE_STATUS.ACTIVE,
      now
    ]
  );

  const sourceId = result.insertId;

  for (const snippet of entry.snippets) {
    const [snippetResult] = await pool.execute(
      `INSERT INTO knowledge_snippets (
        source_id, topic, snippet_text, keywords, effective_from, effective_to, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?)`,
      [
        sourceId,
        snippet.topic,
        snippet.text,
        String(snippet.keywords || '').toLowerCase(),
        Number(snippet.effectiveFrom),
        snippet.effectiveTo === undefined ? null : Number(snippet.effectiveTo),
        now
      ]
    );

    for (const claim of snippet.claims) {
      await pool.execute(
        `INSERT INTO knowledge_claims (snippet_id, claim_text, claim_key, updated_at)
         VALUES (?, ?, ?, ?)
         ON DUPLICATE KEY UPDATE
           claim_text = VALUES(claim_text),
           snippet_id = VALUES(snippet_id),
           updated_at = VALUES(updated_at)`,
        [snippetResult.insertId, claim.text, claim.key, now]
      );
    }
  }

  return { sourceId, snippetCount: entry.snippets.length };
}

/** What is currently loaded, for an operator to see at a glance. */
async function inventory(now = Date.now()) {
  const [rows] = await pool.execute(
    `SELECT k.id, k.publisher, k.title, k.status, k.reviewer,
            k.review_due_date AS reviewDueDate,
            COUNT(s.id) AS snippetCount
       FROM knowledge_sources k
       LEFT JOIN knowledge_snippets s ON s.source_id = k.id
      GROUP BY k.id
      ORDER BY k.review_due_date ASC`
  );

  return rows.map((row) => ({
    ...row,
    reviewDueDate: Number(row.reviewDueDate),
    isOverdue: Number(row.reviewDueDate) < now
  }));
}

module.exports = { validateEntry, loadEntry, inventory, MAX_REVIEW_INTERVAL_DAYS };
