const { GoogleGenAI, Type } = require('@google/genai');

/**
 * Extracts transactions from a receipt, payment screenshot, or statement page
 * using Gemini's vision + structured output.
 *
 * This runs server-side so the API key never ships inside the APK, and so the
 * model can be changed without a Play Store release.
 */

// Every image is tried on the cheap model first. Flash-Lite handles clean
// screenshots and printed receipts, which is most of the traffic.
//
// Note the 2.5-series models are closed to new API keys — they still appear in
// the /models listing but return 404 "no longer available to new users" on
// generateContent. Anything here must be 3.x.
const MODEL = process.env.GEMINI_MODEL || 'gemini-3.1-flash-lite';

// Retried here when the cheap model can't read the image — a blurry photo, a
// faded thermal receipt, or a dense statement page.
// Set to an empty string to disable escalation entirely.
const FALLBACK_MODEL = process.env.GEMINI_FALLBACK_MODEL ?? 'gemini-3-flash';

// Escalation trigger. Below this, the cheap model is telling us it isn't sure —
// a far more reliable signal of a bad image than anything we could measure from
// the pixels before sending them.
const ESCALATE_BELOW_CONFIDENCE = Number(process.env.EXTRACT_ESCALATE_BELOW || 0.7);

// Generous enough for a full statement page (~40 rows); output is billed on
// what is actually produced, not on this ceiling.
const MAX_OUTPUT_TOKENS = 8192;

const SUPPORTED_MEDIA_TYPES = ['image/jpeg', 'image/png', 'image/webp', 'image/heic', 'image/heif'];

const CATEGORIES = [
  'Food', 'Travel', 'Shopping', 'Bills', 'Health',
  'Entertainment', 'Education', 'Groceries', 'Other'
];

/**
 * Response schema. Gemini constrains generation to this shape, so the output is
 * always parseable — no regex extraction, no retry-on-parse loop.
 *
 * Note this is Gemini's OpenAPI-subset dialect, not JSON Schema: types are the
 * uppercase `Type` enum, optionality is `nullable: true` rather than a type
 * union, and `additionalProperties` is not supported. `propertyOrdering` keeps
 * field order stable across calls, which makes responses easier to diff.
 */
const RESPONSE_SCHEMA = {
  type: Type.OBJECT,
  properties: {
    documentType: {
      type: Type.STRING,
      enum: ['RECEIPT', 'PAYMENT_CONFIRMATION', 'STATEMENT', 'OTHER'],
      description: 'What kind of document this image shows.'
    },
    transactions: {
      type: Type.ARRAY,
      description: 'One entry per transaction. Empty if the image contains none.',
      items: {
        type: Type.OBJECT,
        properties: {
          amount: {
            type: Type.NUMBER,
            description:
              "The amount THIS USER paid or received, as a positive number. On a split bill this is the user's own share, not the table total."
          },
          currency: {
            type: Type.STRING,
            description: 'ISO 4217 code, e.g. INR, USD, EUR. Use INR for the rupee sign.'
          },
          date: {
            type: Type.STRING,
            nullable: true,
            description: 'Transaction date as YYYY-MM-DD. Null if the document does not show one.'
          },
          merchant: {
            type: Type.STRING,
            description:
              'The business or person paid. The trading name only - not the status banner, not the location, not the payment app.'
          },
          direction: {
            type: Type.STRING,
            enum: ['DEBIT', 'CREDIT'],
            description: 'DEBIT if the user spent money, CREDIT if they received it.'
          },
          category: { type: Type.STRING, enum: CATEGORIES },
          confidence: {
            type: Type.NUMBER,
            description: 'Your confidence from 0 to 1 that every field above is correct.'
          },
          amountSource: {
            type: Type.STRING,
            description:
              'The exact text you read the amount from, copied verbatim from the image. Used to verify the amount against OCR.'
          }
        },
        required: [
          'amount', 'currency', 'date', 'merchant',
          'direction', 'category', 'confidence', 'amountSource'
        ],
        propertyOrdering: [
          'amount', 'currency', 'date', 'merchant',
          'direction', 'category', 'confidence', 'amountSource'
        ]
      }
    }
  },
  required: ['documentType', 'transactions'],
  propertyOrdering: ['documentType', 'transactions']
};

const SYSTEM_PROMPT = `You extract transaction data from images of receipts, payment confirmations, and bank statements for a personal expense tracker. Accuracy matters more than completeness — a wrong amount is worse than a missing one.

Extracting the amount:
- Record what THIS USER actually paid, which is not always the largest or most prominent figure.
- On a split bill the screen shows both the table total and the user's share (worded as "paid by you", "your share", "you pay", "your portion"). The user's share is the transaction; the total is not.
- Ignore running balances, available balance, reference numbers, transaction IDs, card numbers, phone numbers, and item quantities.
- On a receipt take the payable total, not the subtotal, not the tax line, not the cash tendered, not the change.

Identifying the merchant:
- Give the trading name of the business or the person paid.
- Status banners are not merchants: "Bill cleared", "Payment Successful", "Money Sent", "Order delivered".
- Neither is the location, the payment app (GPay, PhonePe, Paytm), or the bank.
- On a bank statement narration like "POS/RELIANCE FRESH/MUMBAI", the merchant is RELIANCE FRESH.

Dates:
- Return YYYY-MM-DD. Resolve ambiguous numeric dates using other dates visible in the same document.
- Return null rather than guessing a date the document does not show.

Statements: return every transaction row, in the order they appear.

Set confidence below 0.7 on any transaction where you are unsure — those get shown to the user for review rather than imported silently.`;

let client = null;
function getClient() {
  if (!client) {
    if (!process.env.GEMINI_API_KEY) {
      const error = new Error('GEMINI_API_KEY is not configured on the server.');
      error.status = 503;
      throw error;
    }
    client = new GoogleGenAI({ apiKey: process.env.GEMINI_API_KEY });
  }
  return client;
}

/**
 * Extracts transactions, escalating to a stronger vision model when the cheap
 * one can't read the image.
 *
 * Escalation is driven by the model's own reported confidence rather than by
 * inspecting the image first. A blur or contrast metric computed up front can
 * only guess whether a picture is legible; the model that just tried to read it
 * knows. The cost is that an escalated image is billed twice — see `escalated`
 * in the return value, and the per-attempt rows in `attempts`.
 *
 * @param {{ base64: string, mediaType: string, hint?: string }} input
 * @returns {Promise<{ documentType: string, transactions: object[],
 *                     attempts: Array<{model: string, usage: object}>,
 *                     escalated: boolean, model: string }>}
 */
async function extractFromImage({ base64, mediaType, hint }) {
  if (!SUPPORTED_MEDIA_TYPES.includes(mediaType)) {
    const error = new Error(`Unsupported image type: ${mediaType}`);
    error.status = 400;
    throw error;
  }

  const attempts = [];
  const primary = await runExtraction({ base64, mediaType, hint, model: MODEL });
  attempts.push({ model: primary.model, usage: primary.usage });

  if (!FALLBACK_MODEL || FALLBACK_MODEL === MODEL || !needsEscalation(primary)) {
    return {
      documentType: primary.documentType,
      transactions: primary.transactions,
      attempts,
      escalated: false,
      model: primary.model
    };
  }

  let fallback;
  try {
    fallback = await runExtraction({ base64, mediaType, hint, model: FALLBACK_MODEL });
  } catch (error) {
    // The cheap model's result is still the best we have — a failed retry must
    // not turn a usable extraction into an error.
    return {
      documentType: primary.documentType,
      transactions: primary.transactions,
      attempts,
      escalated: false,
      model: primary.model
    };
  }

  attempts.push({ model: fallback.model, usage: fallback.usage });

  // Keep the stronger model's answer only if it actually did better. On a truly
  // illegible image it may also return nothing, and the primary's partial read
  // is more useful to the user than an empty result.
  const useFallback =
    fallback.transactions.length > 0 &&
    (primary.transactions.length === 0 ||
      minConfidence(fallback.transactions) >= minConfidence(primary.transactions));

  const winner = useFallback ? fallback : primary;
  return {
    documentType: winner.documentType,
    transactions: winner.transactions,
    attempts,
    escalated: useFallback,
    model: winner.model
  };
}

/** True when the cheap model returned nothing, or wasn't confident in what it read. */
function needsEscalation(result) {
  if (result.transactions.length === 0) return true;
  return minConfidence(result.transactions) < ESCALATE_BELOW_CONFIDENCE;
}

function minConfidence(transactions) {
  if (transactions.length === 0) return 0;
  return Math.min(...transactions.map((t) => Number(t.confidence) || 0));
}

async function runExtraction({ base64, mediaType, hint, model }) {
  const userText = hint
    ? `Extract the transactions from this image. Context from the app: ${hint}`
    : 'Extract the transactions from this image.';

  let response;
  try {
    response = await callModel({ base64, mediaType, userText, model });
  } catch (cause) {
    // Provider errors carry their own HTTP status, and forwarding it verbatim is
    // actively misleading: a 404 from Gemini ("this model is not available to
    // your key") reaches the client as a 404, which reads as "the /extract route
    // doesn't exist" and sends you debugging the wrong layer entirely. Report
    // upstream failures as a bad gateway, and keep the real cause in the message.
    const error = new Error(upstreamMessage(cause, model));
    error.status = 502;
    error.cause = cause;
    throw error;
  }
  // Check why generation stopped before reading the text. A safety block or a
  // truncated response still returns a 200 with no usable JSON, so parsing
  // first would throw a confusing SyntaxError instead of a clear message.
  const finishReason = response.candidates?.[0]?.finishReason;
  if (finishReason === 'MAX_TOKENS') {
    const error = new Error('This document has too many transactions to extract in one pass.');
    error.status = 413;
    throw error;
  }
  if (finishReason && finishReason !== 'STOP') {
    const error = new Error(`The model could not process this image (${finishReason}).`);
    error.status = 422;
    throw error;
  }

  const text = response.text;
  if (!text) {
    const error = new Error('The model returned no extraction result.');
    error.status = 502;
    throw error;
  }

  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch (cause) {
    const error = new Error('The model returned malformed extraction output.');
    error.status = 502;
    throw error;
  }

  const usage = response.usageMetadata || {};
  return {
    documentType: parsed.documentType,
    transactions: Array.isArray(parsed.transactions) ? parsed.transactions : [],
    usage: {
      // Thinking tokens bill as output on Gemini, so fold them in rather than
      // under-reporting the cost of a call.
      inputTokens: usage.promptTokenCount || 0,
      outputTokens: (usage.candidatesTokenCount || 0) + (usage.thoughtsTokenCount || 0)
    },
    model
  };
}

/**
 * Cleans up payee strings pulled out of bank narrations.
 *
 * Banks truncate the payee before it ever reaches the PDF — "Amazon India"
 * arrives as "Amazon I", "IRCTC Ticket" as "IRCTC Ti". No parser can recover a
 * string the bank already discarded, but a model recognises the brand behind the
 * fragment. This is a text task, so it costs a fraction of an image call and one
 * request covers an entire statement.
 *
 * @param {string[]} names Payee fragments, already deduplicated by the caller.
 * @returns {Promise<{ merchants: object[], usage: object, model: string }>}
 */
async function enrichMerchants(names) {
  if (!Array.isArray(names) || names.length === 0) {
    return { merchants: [], usage: { inputTokens: 0, outputTokens: 0 }, model: MODEL };
  }

  const numbered = names.map((name, index) => `${index}. ${name}`).join('\n');

  let response;
  try {
    response = await getClient().models.generateContent({
      model: MODEL,
      contents: [{ role: 'user', parts: [{ text: `Clean up these payee names:\n${numbered}` }] }],
      config: {
        systemInstruction: MERCHANT_PROMPT,
        responseMimeType: 'application/json',
        responseSchema: MERCHANT_SCHEMA,
        maxOutputTokens: MAX_OUTPUT_TOKENS,
        temperature: 0
      }
    });
  } catch (cause) {
    const error = new Error(upstreamMessage(cause, MODEL));
    error.status = 502;
    error.cause = cause;
    throw error;
  }

  if (response.candidates?.[0]?.finishReason === 'MAX_TOKENS') {
    const error = new Error('Too many merchant names to clean up in one request.');
    error.status = 413;
    throw error;
  }

  let parsed;
  try {
    parsed = JSON.parse(response.text || '{}');
  } catch (cause) {
    const error = new Error('The model returned malformed merchant output.');
    error.status = 502;
    throw error;
  }

  const usage = response.usageMetadata || {};
  return {
    merchants: Array.isArray(parsed.merchants) ? parsed.merchants : [],
    usage: {
      inputTokens: usage.promptTokenCount || 0,
      outputTokens: (usage.candidatesTokenCount || 0) + (usage.thoughtsTokenCount || 0)
    },
    model: MODEL
  };
}

const MERCHANT_SCHEMA = {
  type: Type.OBJECT,
  properties: {
    merchants: {
      type: Type.ARRAY,
      items: {
        type: Type.OBJECT,
        properties: {
          index: {
            type: Type.INTEGER,
            description: 'The number this name was listed under in the request.'
          },
          merchant: {
            type: Type.STRING,
            description: 'The cleaned-up name, or the original if you cannot improve on it.'
          },
          category: { type: Type.STRING, enum: CATEGORIES },
          confidence: {
            type: Type.NUMBER,
            description: '0 to 1. Low when you are guessing at what a truncated fragment means.'
          }
        },
        required: ['index', 'merchant', 'category', 'confidence'],
        propertyOrdering: ['index', 'merchant', 'category', 'confidence']
      }
    }
  },
  required: ['merchants'],
  propertyOrdering: ['merchants']
};

const MERCHANT_PROMPT = `You are given payee names extracted from Indian bank statement narrations. The bank truncates them to a fixed width, so many are cut off mid-word.

For each one, return the most likely real merchant or person, and a spending category.

Expanding truncated names:
- Expand only when the fragment clearly identifies a well-known brand: "Amazon I" is Amazon, "IRCTC Ti" is IRCTC, "Flipkar" is Flipkart, "Swigg" is Swiggy.
- If a fragment is ambiguous or you do not recognise it, return it unchanged rather than guessing. A wrong expansion is worse than a truncated one.
- Personal names stay as they are — do not invent surnames or expand initials.

Formatting:
- Use the brand's normal capitalisation: "IRCTC" not "Irctc", "Westside" not "WESTSIDE".
- Drop payment-processor noise: paytm, razorpay, billdesk, payu, pinelabs, and bank codes like YESB, SBIN, HDFC, UTIB, RATN.
- Return the name only — no city, no branch, no reference number.

Set confidence below 0.6 when you are unsure what a fragment means, and return one entry per input, using the number it was listed under.`;

/**
 * Writes a short plain-English summary of a period's spending.
 *
 * Takes figures the app has already computed rather than transactions. That is
 * partly cost — a few hundred tokens instead of a whole statement — but mostly
 * scope: a summary needs totals, and sending individual transaction rows to a
 * third party to produce them would be handing over far more than the job
 * requires.
 *
 * @param {object} figures Pre-aggregated totals, categories and merchants.
 * @returns {Promise<{ headline: string, narrative: string, suggestions: string[],
 *                     usage: object, model: string }>}
 */
async function narrateSpending(figures) {
  let response;
  try {
    response = await getClient().models.generateContent({
      model: MODEL,
      contents: [{
        role: 'user',
        parts: [{ text: `Summarise this period:\n${JSON.stringify(figures, null, 2)}` }]
      }],
      config: {
        systemInstruction: NARRATIVE_PROMPT,
        responseMimeType: 'application/json',
        responseSchema: NARRATIVE_SCHEMA,
        maxOutputTokens: NARRATIVE_MAX_OUTPUT_TOKENS,
        // A little variation reads better than the same four sentences every
        // month, but not enough to start reinterpreting the numbers.
        temperature: 0.4
      }
    });
  } catch (cause) {
    const error = new Error(upstreamMessage(cause, MODEL));
    error.status = 502;
    error.cause = cause;
    throw error;
  }

  let parsed;
  try {
    parsed = JSON.parse(response.text || '{}');
  } catch (cause) {
    const error = new Error('The model returned a malformed summary.');
    error.status = 502;
    throw error;
  }

  const usage = response.usageMetadata || {};
  return {
    headline: String(parsed.headline || '').trim(),
    narrative: String(parsed.narrative || '').trim(),
    suggestions: (Array.isArray(parsed.suggestions) ? parsed.suggestions : [])
      .map((item) => String(item).trim())
      .filter(Boolean)
      .slice(0, MAX_SUGGESTIONS),
    usage: {
      inputTokens: usage.promptTokenCount || 0,
      outputTokens: (usage.candidatesTokenCount || 0) + (usage.thoughtsTokenCount || 0)
    },
    model: MODEL
  };
}

// A summary is a few sentences. Capping output keeps both the cost and the
// model's temptation to editorialise in check.
const NARRATIVE_MAX_OUTPUT_TOKENS = 700;
const MAX_SUGGESTIONS = 3;

const NARRATIVE_SCHEMA = {
  type: Type.OBJECT,
  properties: {
    headline: {
      type: Type.STRING,
      description: 'At most 8 words capturing the period. No trailing full stop.'
    },
    narrative: {
      type: Type.STRING,
      description: 'Two or three sentences describing what happened, in plain English.'
    },
    suggestions: {
      type: Type.ARRAY,
      description: 'Up to 3 short, concrete observations the user could act on. May be empty.',
      items: { type: Type.STRING }
    }
  },
  required: ['headline', 'narrative', 'suggestions'],
  propertyOrdering: ['headline', 'narrative', 'suggestions']
};

const NARRATIVE_PROMPT = `You write a short summary of one person's spending for a period, from figures that have already been calculated for you.

Rules about the numbers:
- Use only the figures given. Never calculate a new one, never estimate, and never state a number that is not in the input.
- Amounts are in the currency named in the input. Write them with that currency's symbol.
- If a figure is absent, say nothing about it. Do not infer income from spending, or a trend from a single period.

Tone:
- Plain, factual, second person. "You spent" not "The user spent".
- Neutral. Do not praise, scold, or moralise about what was bought. Nobody needs a lecture about ordering takeaway.
- No greetings, no sign-off, no emoji.

Suggestions:
- Only concrete things grounded in the figures given, e.g. "Food is your largest category at 40% — worth a look if you want to cut back."
- Never recommend investments, products, financial services, or debt decisions. You are not a financial adviser.
- If the figures suggest nothing useful, return an empty list. An empty list is a perfectly good answer.`;

function callModel({ base64, mediaType, userText, model }) {
  return getClient().models.generateContent({
    model,
    contents: [{
      role: 'user',
      parts: [
        { inlineData: { mimeType: mediaType, data: base64 } },
        { text: userText }
      ]
    }],
    config: {
      systemInstruction: SYSTEM_PROMPT,
      responseMimeType: 'application/json',
      responseSchema: RESPONSE_SCHEMA,
      maxOutputTokens: MAX_OUTPUT_TOKENS,
      // Extraction is a reading task, not a creative one — pin it to the most
      // likely reading so repeated imports of the same file agree.
      temperature: 0
    }
  });
}

/**
 * Turns a provider error into something a developer can act on.
 *
 * The SDK stringifies the whole JSON error body into `message`, so the useful
 * sentence is buried. Pull it out and name the model, because "not available"
 * and "quota exceeded" need completely different fixes.
 */
function upstreamMessage(cause, model) {
  let detail = cause?.message || 'unknown error';
  try {
    const parsed = JSON.parse(detail);
    if (parsed?.error?.message) detail = parsed.error.message;
  } catch (ignored) {
    // Not JSON — use the message as-is.
  }
  return `Model "${model}" failed: ${detail}`;
}

module.exports = {
  extractFromImage,
  enrichMerchants,
  narrateSpending,
  SUPPORTED_MEDIA_TYPES,
  CATEGORIES,
  MODEL,
  FALLBACK_MODEL,
  ESCALATE_BELOW_CONFIDENCE
};
