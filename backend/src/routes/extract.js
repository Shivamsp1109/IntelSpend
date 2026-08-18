const express = require('express');
const { pool } = require('../config/db');
const { requireFirebaseAuth } = require('../middleware/auth');
const {
  extractFromImage,
  enrichMerchants,
  narrateSpending,
  SUPPORTED_MEDIA_TYPES
} = require('../services/visionExtraction');
const { sanitiseFigures } = require('../services/narrativeFigures');
const { categorise, MAX_ITEMS: MAX_CATEGORY_ITEMS } = require('../services/categorisation');
const { modelLimiter, syncLimiter } = require('../middleware/rateLimit');
// Shared with chat, which keeps its own budget. One combined cap would let a
// year of statement imports exhaust somebody's ability to ask a question.
const {
  countCallsThisMonth, recordUsage, startOfMonthMillis, FEATURE
} = require('../services/modelUsage');

const router = express.Router();

// Base64 inflates by ~33%, so this caps the decoded image at roughly 5 MB.
// Larger images cost more tokens without improving extraction — the client
// downsamples before upload.
const MAX_BASE64_CHARS = 7_000_000;

// Per-user monthly ceiling. A runaway client loop or a shared account can't run
// up an unbounded bill; the user sees a clear error instead.
const MONTHLY_CALL_CAP = Number(process.env.EXTRACT_MONTHLY_CAP || 500);

// Names are deduplicated client-side, so this comfortably covers a long
// statement while bounding the size of a single request.
const MAX_MERCHANT_NAMES = 200;

// Per million tokens, per model, used to record an estimated cost alongside each
// call so spend is visible in the database. Escalation means two models can bill
// against one import, so the rate has to be looked up per attempt rather than
// assumed. Unknown models fall back to the primary rate.
const COST_PER_MTOK = {
  'gemini-3.1-flash-lite': { input: 0.25, output: 1.50 },
  'gemini-3-flash': { input: 0.50, output: 3.00 },
  'gemini-3.5-flash-lite': { input: 0.30, output: 2.50 },
  'gemini-3.5-flash': { input: 1.50, output: 9.00 }
};
const DEFAULT_INPUT_COST_PER_MTOK = Number(process.env.EXTRACT_INPUT_COST_PER_MTOK || 0.25);
const DEFAULT_OUTPUT_COST_PER_MTOK = Number(process.env.EXTRACT_OUTPUT_COST_PER_MTOK || 1.50);

router.post('/', requireFirebaseAuth, modelLimiter, async (req, res, next) => {
  try {
    const { imageBase64, mediaType, hint } = req.body || {};

    if (typeof imageBase64 !== 'string' || imageBase64.length === 0) {
      throw createBadRequest('Missing imageBase64.');
    }
    if (imageBase64.length > MAX_BASE64_CHARS) {
      throw createBadRequest('Image is too large. Resize it below 5 MB and try again.');
    }
    if (!SUPPORTED_MEDIA_TYPES.includes(mediaType)) {
      throw createBadRequest(`mediaType must be one of: ${SUPPORTED_MEDIA_TYPES.join(', ')}`);
    }

    const usedThisMonth = await countCallsThisMonth(req.user.uid, FEATURE.EXTRACT);
    if (usedThisMonth >= MONTHLY_CALL_CAP) {
      const error = new Error(
        `Monthly extraction limit of ${MONTHLY_CALL_CAP} reached. It resets at the start of next month.`
      );
      error.status = 429;
      throw error;
    }

    const result = await extractFromImage({ base64: imageBase64, mediaType, hint });

    // One row per model attempt — an escalated image really did cost two calls,
    // and the cap counts rows, so it reflects actual spend rather than imports.
    // Logged after the call so a failed extraction isn't billed against the cap.
    for (const attempt of result.attempts) {
      await recordUsage({ uid: req.user.uid, feature: FEATURE.EXTRACT, model: attempt.model, usage: attempt.usage });
    }

    return res.json({
      documentType: result.documentType,
      transactions: result.transactions,
      model: result.model,
      escalated: result.escalated,
      callsUsedThisMonth: usedThisMonth + result.attempts.length,
      monthlyCallCap: MONTHLY_CALL_CAP
    });
  } catch (error) {
    return next(error);
  }
});

/**
 * Cleans up payee names pulled from bank statement narrations.
 *
 * Separate from the image endpoint because it is a text task: one request covers
 * a whole statement regardless of page count, and costs a fraction of a single
 * image call. Amounts and dates stay deterministic — only the name is modelled.
 */
router.post('/merchants', requireFirebaseAuth, modelLimiter, async (req, res, next) => {
  try {
    const names = req.body?.names;
    if (!Array.isArray(names) || names.length === 0) {
      throw createBadRequest('Missing names array.');
    }
    if (names.length > MAX_MERCHANT_NAMES) {
      throw createBadRequest(`At most ${MAX_MERCHANT_NAMES} names per request.`);
    }
    if (!names.every((name) => typeof name === 'string' && name.length <= 200)) {
      throw createBadRequest('Each name must be a string of at most 200 characters.');
    }

    const usedThisMonth = await countCallsThisMonth(req.user.uid, FEATURE.EXTRACT);
    if (usedThisMonth >= MONTHLY_CALL_CAP) {
      const error = new Error(
        `Monthly extraction limit of ${MONTHLY_CALL_CAP} reached. It resets at the start of next month.`
      );
      error.status = 429;
      throw error;
    }

    const result = await enrichMerchants(names);
    await recordUsage({ uid: req.user.uid, feature: FEATURE.EXTRACT, model: result.model, usage: result.usage });

    return res.json({ merchants: result.merchants, model: result.model });
  } catch (error) {
    return next(error);
  }
});

/**
 * Classifies merchants the device could not place on its own.
 *
 * The client sends only what it could not resolve from the user's own
 * corrections or the bundled merchant map, deduplicated by merchant — so a
 * month of daily coffees is one entry here, not thirty. That is what keeps this
 * costing rupees rather than being the expensive part of an import.
 */
router.post('/categorise', requireFirebaseAuth, modelLimiter, async (req, res, next) => {
  try {
    const items = req.body?.items;
    if (!Array.isArray(items) || items.length === 0) {
      throw createBadRequest('Missing items array.');
    }
    if (items.length > MAX_CATEGORY_ITEMS) {
      throw createBadRequest(`At most ${MAX_CATEGORY_ITEMS} items per request.`);
    }

    const cleaned = items.map((item, index) => {
      const merchant = typeof item?.merchant === 'string' ? item.merchant.trim() : '';
      if (!merchant) throw createBadRequest(`Item ${index} is missing a merchant.`);
      return {
        merchant: merchant.slice(0, 120),
        // Bounded because it comes out of a parsed statement, which is to say
        // out of a document we did not write.
        narration: typeof item?.narration === 'string' ? item.narration.trim().slice(0, 200) : '',
        amount: Number.isFinite(Number(item?.amount)) ? Math.abs(Number(item.amount)) : undefined
      };
    });

    const usedThisMonth = await countCallsThisMonth(req.user.uid, FEATURE.EXTRACT);
    if (usedThisMonth >= MONTHLY_CALL_CAP) {
      const error = new Error(
        `Monthly limit of ${MONTHLY_CALL_CAP} model calls reached. It resets at the start of next month.`
      );
      error.status = 429;
      throw error;
    }

    const result = await categorise(cleaned);
    await recordUsage({ uid: req.user.uid, feature: FEATURE.EXTRACT, model: result.model, usage: result.usage });

    return res.json({ results: result.results, model: result.model });
  } catch (error) {
    return next(error);
  }
});

/**
 * Writes a plain-English summary of a period from figures the app has already
 * calculated.
 *
 * The payload is rebuilt field by field rather than forwarded, for two reasons.
 * It bounds what reaches the model — no transaction rows, no note fields, no
 * ids. And the text that does go through (merchant and category names) was
 * itself read out of user-supplied documents, so it is untrusted input heading
 * into a prompt; capping it and pinning the response schema keeps a merchant
 * called "ignore previous instructions" from being able to do anything with it.
 */
router.post('/narrative', requireFirebaseAuth, modelLimiter, async (req, res, next) => {
  try {
    const figures = sanitiseFigures(req.body);

    const usedThisMonth = await countCallsThisMonth(req.user.uid, FEATURE.EXTRACT);
    if (usedThisMonth >= MONTHLY_CALL_CAP) {
      const error = new Error(
        `Monthly limit of ${MONTHLY_CALL_CAP} model calls reached. It resets at the start of next month.`
      );
      error.status = 429;
      throw error;
    }

    const result = await narrateSpending(figures);
    await recordUsage({ uid: req.user.uid, feature: FEATURE.EXTRACT, model: result.model, usage: result.usage });

    return res.json({
      headline: result.headline,
      narrative: result.narrative,
      suggestions: result.suggestions,
      model: result.model,
      callsUsedThisMonth: usedThisMonth + 1,
      monthlyCallCap: MONTHLY_CALL_CAP
    });
  } catch (error) {
    return next(error);
  }
});

/** Current spend and remaining quota, so the app can show it in settings. */
router.get('/usage', requireFirebaseAuth, syncLimiter, async (req, res, next) => {
  try {
    const [rows] = await pool.execute(
      `SELECT COUNT(*)                AS calls,
              COALESCE(SUM(input_tokens), 0)  AS input_tokens,
              COALESCE(SUM(output_tokens), 0) AS output_tokens,
              COALESCE(SUM(estimated_cost_usd), 0) AS cost_usd
         FROM llm_usage
        -- Scoped to extraction, because the figure is reported against
        -- extraction's cap. Counting chat here would show the quota shrinking
        -- for a reason the screen does not mention.
        WHERE uid = ? AND feature = ? AND created_at >= ?`,
      [req.user.uid, FEATURE.EXTRACT, startOfMonthMillis()]
    );

    const row = rows[0] || {};
    return res.json({
      callsThisMonth: Number(row.calls || 0),
      monthlyCallCap: MONTHLY_CALL_CAP,
      inputTokens: Number(row.input_tokens || 0),
      outputTokens: Number(row.output_tokens || 0),
      estimatedCostUsd: Number(row.cost_usd || 0)
    });
  } catch (error) {
    return next(error);
  }
});

function createBadRequest(message) {
  const error = new Error(message);
  error.status = 400;
  return error;
}

module.exports = router;
