const { GoogleGenAI, Type } = require('@google/genai');
const { CATEGORIES, NATURES } = require('./taxonomy');

/**
 * Classifies merchants the device could not resolve on its own.
 *
 * Deliberately the last step in a cascade, not the first. The app checks what
 * the user has previously corrected, then a bundled merchant map, and only asks
 * here for what remains — deduplicated, so a hundred Swiggy orders are one
 * entry in one request rather than a hundred calls. Answers are written back to
 * the device's learned store, so a merchant is paid for once and never again.
 *
 * Two things are asked at once because they are one judgement. "HDFC CC PAYMENT"
 * is not a Bills category, it is a credit-card payment and not spending at all;
 * deciding the category without deciding that would file it under something
 * plausible and leave it inflating the user's month.
 */

const MODEL = process.env.GEMINI_CATEGORY_MODEL || process.env.GEMINI_MODEL || 'gemini-3.1-flash-lite';

// Each answer is a category, a nature and a number. 40 tokens is generous per
// item; this covers a full batch with room to spare.
const MAX_OUTPUT_TOKENS = 4096;

/** Matches the client's batch size. Bigger requests risk the output ceiling. */
const MAX_ITEMS = 100;

const RESPONSE_SCHEMA = {
  type: Type.OBJECT,
  properties: {
    results: {
      type: Type.ARRAY,
      items: {
        type: Type.OBJECT,
        properties: {
          index: {
            type: Type.INTEGER,
            description: 'The number this merchant was listed under in the request.'
          },
          category: { type: Type.STRING, enum: CATEGORIES },
          nature: { type: Type.STRING, enum: NATURES },
          confidence: {
            type: Type.NUMBER,
            description: '0 to 1. Low when the merchant name is too vague to place.'
          }
        },
        required: ['index', 'category', 'nature', 'confidence'],
        propertyOrdering: ['index', 'category', 'nature', 'confidence']
      }
    }
  },
  required: ['results'],
  propertyOrdering: ['results']
};

const SYSTEM_PROMPT = `You classify transactions from an Indian personal expense tracker. For each one give a spending category and a nature.

Nature comes first, because it decides whether the transaction is spending at all:
- Spending — money actually spent on goods or services. Most transactions.
- SelfTransfer — moved between the user's own accounts. "TRANSFER TO SELF", "OWN ACCOUNT", NEFT to a same-name account.
- LoanRepayment — loan or EMI instalment. "EMI", "LOAN REPAYMENT", "HOME LOAN".
- CreditCardPayment — settling a card bill. "CC PAYMENT", "CREDIT CARD BILL", "AUTOPAY CARD".
- Investment — mutual funds, stocks, SIP, brokerages: Zerodha, Groww, Upstox, Coin, "SIP".
- Savings — recurring or fixed deposits, PPF, "RD", "FD".
- CashWithdrawal — ATM cash. "ATM WDL", "CASH WITHDRAWAL".
- Refund — money returned by a merchant.
- Income — salary, interest, dividends, money received.

Everything that is not Spending still needs a category; use the closest one, or "Other". The nature is what matters for those.

Choosing a category:
- Use the narration and amount, not the merchant name alone. "IRCTC" with a ticket narration is Travel; a ₹40 platform fee is Transport.
- Food & Dining is restaurants, cafés and delivery. Groceries is provisions and supermarkets — Swiggy Instamart, Blinkit, Zepto, BigBasket, DMart, Reliance Fresh.
- Transport is local travel: autos, cabs, metro, bus. Travel is long distance: flights, trains, hotels. Fuel is petrol and diesel only.
- Utilities is electricity, water, gas and piped services. Mobile & Internet is phone and broadband. Subscriptions is recurring digital services: Netflix, Spotify, Prime, cloud storage.
- Bank Fees & Charges covers bank charges, penalties, and GST levied on them.

Confidence:
- Below 0.6 when the name is a bare personal name, an unrecognisable code, or too generic to place. Those are shown to the user rather than applied.
- Do not guess a specific category to seem helpful. "Other" with honest confidence is more useful than a confident wrong answer, because the user sees and can fix Other, whereas a plausible wrong category hides.

Return one result per input, using the number it was listed under.`;

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
 * @param {Array<{merchant: string, narration?: string, amount?: number}>} items
 * @returns {Promise<{ results: object[], usage: object, model: string }>}
 */
async function categorise(items) {
  if (!Array.isArray(items) || items.length === 0) {
    return { results: [], usage: { inputTokens: 0, outputTokens: 0 }, model: MODEL };
  }

  const listed = items
    .slice(0, MAX_ITEMS)
    .map((item, index) => {
      const parts = [`${index}. Merchant: ${item.merchant}`];
      if (item.narration) parts.push(`   Narration: ${item.narration}`);
      if (Number.isFinite(item.amount)) parts.push(`   Amount: ${item.amount}`);
      return parts.join('\n');
    })
    .join('\n');

  let response;
  try {
    response = await getClient().models.generateContent({
      model: MODEL,
      contents: [{ role: 'user', parts: [{ text: `Classify these transactions:\n${listed}` }] }],
      config: {
        systemInstruction: SYSTEM_PROMPT,
        responseMimeType: 'application/json',
        responseSchema: RESPONSE_SCHEMA,
        maxOutputTokens: MAX_OUTPUT_TOKENS,
        temperature: 0,
        // Classification against a fixed list is recall, not reasoning, and
        // thinking tokens bill as output. Disabling it is most of the reason a
        // thousand merchants costs rupees rather than tens of them.
        thinkingConfig: { thinkingBudget: 0 }
      }
    });
  } catch (cause) {
    const error = new Error(upstreamMessage(cause, MODEL));
    error.status = 502;
    error.cause = cause;
    throw error;
  }

  if (response.candidates?.[0]?.finishReason === 'MAX_TOKENS') {
    const error = new Error('Too many merchants to classify in one request.');
    error.status = 413;
    throw error;
  }

  let parsed;
  try {
    parsed = JSON.parse(response.text || '{}');
  } catch (cause) {
    const error = new Error('The model returned malformed classification output.');
    error.status = 502;
    throw error;
  }

  const usage = response.usageMetadata || {};
  return {
    // Validated rather than forwarded: an answer outside the vocabulary would
    // reach the app, match no enum entry and land in Other — the precise bug
    // this endpoint exists to remove.
    results: (Array.isArray(parsed.results) ? parsed.results : [])
      .filter((row) => Number.isInteger(row?.index) && row.index >= 0 && row.index < items.length)
      .map((row) => ({
        index: row.index,
        category: CATEGORIES.includes(row.category) ? row.category : 'Other',
        nature: NATURES.includes(row.nature) ? row.nature : 'Spending',
        confidence: Math.max(0, Math.min(1, Number(row.confidence) || 0))
      })),
    usage: {
      inputTokens: usage.promptTokenCount || 0,
      outputTokens: (usage.candidatesTokenCount || 0) + (usage.thoughtsTokenCount || 0)
    },
    model: MODEL
  };
}

/** Same unwrapping as the other services — see visionExtraction.upstreamMessage. */
function upstreamMessage(cause, model) {
  let detail = cause?.message || 'unknown error';
  try {
    const parsed = JSON.parse(detail);
    if (parsed?.error?.message) detail = parsed.error.message;
  } catch (ignored) {
    // Not JSON — use as-is.
  }
  return `Model "${model}" failed: ${detail}`;
}

module.exports = { categorise, MAX_ITEMS };
