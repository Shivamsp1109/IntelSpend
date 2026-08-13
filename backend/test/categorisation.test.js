/**
 * Merchant classification.
 *
 * Run with: npm test
 *
 * The validation matters more than the model call. An answer outside the shared
 * vocabulary reaches the app, matches no enum entry and lands in "Other" — which
 * is the precise failure this endpoint was built to remove, so letting one
 * through here would be quietly self-defeating.
 */
const assert = require('assert');
const Module = require('module');

const calls = [];
let queue = [];

const STUB_ID = require.resolve('./stub-genai.js');
const originalResolve = Module._resolveFilename;
Module._resolveFilename = function (request, ...rest) {
  if (request === '@google/genai') return STUB_ID;
  return originalResolve.call(this, request, ...rest);
};

require.cache[STUB_ID] = {
  id: STUB_ID,
  filename: STUB_ID,
  loaded: true,
  exports: {
    Type: {
      STRING: 'STRING', NUMBER: 'NUMBER', INTEGER: 'INTEGER',
      BOOLEAN: 'BOOLEAN', ARRAY: 'ARRAY', OBJECT: 'OBJECT'
    },
    GoogleGenAI: class StubGoogleGenAI {
      constructor() {
        this.models = {
          generateContent: async (params) => {
            calls.push(params);
            const next = queue.shift();
            if (!next) throw new Error('No stubbed response left');
            if (next instanceof Error) throw next;
            return next;
          }
        };
      }
    }
  }
};

process.env.GEMINI_API_KEY = 'test-key';
const { categorise } = require('../src/services/categorisation.js');
const { CATEGORIES, NATURES } = require('../src/services/taxonomy.js');

function reply(results) {
  return {
    candidates: [{ finishReason: 'STOP' }],
    text: JSON.stringify({ results }),
    usageMetadata: { promptTokenCount: 900, candidatesTokenCount: 200, thoughtsTokenCount: 0 }
  };
}

const ITEMS = [
  { merchant: 'Swiggy Instamart', narration: 'UPI/DR/1234/INSTAMART', amount: 640 },
  { merchant: 'HDFC CC PAYMENT', narration: 'CREDIT CARD BILL', amount: 18000 }
];

function ok(name, fn) {
  fn();
  console.log(`  ok  ${name}`);
}

(async () => {
  console.log('merchant categorisation');

  calls.length = 0;
  queue = [reply([
    { index: 0, category: 'Groceries', nature: 'Spending', confidence: 0.94 },
    { index: 1, category: 'Other', nature: 'CreditCardPayment', confidence: 0.9 }
  ])];
  const result = await categorise(ITEMS);

  ok('answers come back paired to what was asked', () => {
    assert.strictEqual(result.results.length, 2);
    assert.deepStrictEqual(result.results[0], {
      index: 0, category: 'Groceries', nature: 'Spending', confidence: 0.94
    });
  });

  // The reason nature is asked for at all: a card payment is not spending, and
  // no category alone can express that.
  ok('a card payment is marked as not spending', () => {
    assert.strictEqual(result.results[1].nature, 'CreditCardPayment');
  });

  ok('the request carries narration and amount, not just the name', () => {
    const sent = calls[0].contents[0].parts[0].text;
    assert.ok(sent.includes('Swiggy Instamart'));
    assert.ok(sent.includes('INSTAMART'), 'narration disambiguates the merchant');
    assert.ok(sent.includes('640'), 'amount disambiguates too');
  });

  // Classification against a fixed list is recall, not reasoning, and thinking
  // tokens bill as output — this is most of why a thousand merchants is cheap.
  ok('thinking is disabled', () => {
    assert.strictEqual(calls[0].config.thinkingConfig.thinkingBudget, 0);
    assert.strictEqual(calls[0].config.temperature, 0);
  });

  ok('every schema value is one the app understands', () => {
    const schema = calls[0].config.responseSchema.properties.results.items.properties;
    assert.deepStrictEqual(schema.category.enum, CATEGORIES);
    assert.deepStrictEqual(schema.nature.enum, NATURES);
    assert.ok(CATEGORIES.includes('Groceries'), 'the app has a home for this');
    assert.ok(CATEGORIES.includes('Education'), 'and for this');
  });

  // The guard against recreating the original bug.
  calls.length = 0;
  queue = [reply([
    { index: 0, category: 'Made Up Category', nature: 'Teleportation', confidence: 0.9 }
  ])];
  const invented = await categorise([ITEMS[0]]);

  ok('a value outside the vocabulary degrades rather than escaping', () => {
    assert.strictEqual(invented.results[0].category, 'Other');
    assert.strictEqual(invented.results[0].nature, 'Spending');
  });

  calls.length = 0;
  queue = [reply([
    { index: 99, category: 'Groceries', nature: 'Spending', confidence: 0.9 },
    { index: -1, category: 'Travel', nature: 'Spending', confidence: 0.9 }
  ])];
  const outOfRange = await categorise([ITEMS[0]]);

  ok('an index pointing at nothing is dropped', () => {
    assert.strictEqual(outOfRange.results.length, 0);
  });

  calls.length = 0;
  queue = [reply([{ index: 0, category: 'Groceries', nature: 'Spending', confidence: 7 }])];
  const wild = await categorise([ITEMS[0]]);

  ok('confidence is clamped to a fraction', () => {
    assert.strictEqual(wild.results[0].confidence, 1);
  });

  ok('an empty request costs nothing', async () => {
    // Deliberately not awaited inside ok(); checked below.
  });
  const empty = await categorise([]);
  assert.deepStrictEqual(empty.results, []);
  assert.strictEqual(empty.usage.inputTokens, 0);

  calls.length = 0;
  queue = [Object.assign(
    new Error('{"error":{"code":404,"message":"model gone","status":"NOT_FOUND"}}'),
    { status: 404 }
  )];
  try {
    await categorise([ITEMS[0]]);
    throw new Error('expected a rejection');
  } catch (error) {
    assert.strictEqual(error.status, 502, 'provider failure must not masquerade as a 404');
    console.log('  ok  a provider failure is reported as 502');
  }

  console.log('\nall categorisation tests passed');
})().catch((error) => {
  console.error('\nFAILED:', error.message);
  process.exit(1);
});
