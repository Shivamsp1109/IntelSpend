/**
 * Narrative payload validation and generation.
 *
 * Run with: npm test
 *
 * Two things are being checked. That nothing beyond the declared fields can
 * reach the model — the endpoint's whole privacy claim rests on that. And that
 * the response is clamped to a shape the app can render, since a model that
 * ignores the schema should not be able to hand the UI a surprise.
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
const { narrateSpending } = require('../src/services/visionExtraction.js');
const { sanitiseFigures } = require('../src/services/narrativeFigures.js');

function reply(payload) {
  return {
    candidates: [{ finishReason: 'STOP' }],
    text: JSON.stringify(payload),
    usageMetadata: { promptTokenCount: 400, candidatesTokenCount: 120, thoughtsTokenCount: 30 }
  };
}

const VALID = {
  periodLabel: 'August 2026',
  currency: 'INR',
  totalExpense: 40000,
  totalIncome: 60000,
  previousExpense: 35000,
  averagePerDay: 1290.333,
  transactionCount: 62,
  topCategories: [{ name: 'Food', amount: 12000 }],
  topMerchants: [{ name: 'Swiggy', amount: 8000, count: 40 }],
  highlights: ['Spending is up 14%: more than the period before.']
};

function ok(name, fn) {
  fn();
  console.log(`  ok  ${name}`);
}

function rejects(name, body, pattern) {
  try {
    sanitiseFigures(body);
    throw new Error(`expected a rejection for ${name}`);
  } catch (error) {
    assert.strictEqual(error.status, 400, `${name} should be a 400`);
    assert.ok(pattern.test(error.message), `${name}: got "${error.message}"`);
    console.log(`  ok  ${name}`);
  }
}

(async () => {
  console.log('narrative figures');

  ok('a valid payload passes through with its figures intact', () => {
    const clean = sanitiseFigures(VALID);
    assert.strictEqual(clean.periodLabel, 'August 2026');
    assert.strictEqual(clean.totalExpense, 40000);
    assert.strictEqual(clean.transactionCount, 62);
    assert.strictEqual(clean.topMerchants[0].count, 40);
    // Rounded to paise; a long float would waste tokens on noise.
    assert.strictEqual(clean.averagePerDay, 1290.33);
  });

  // The privacy claim on this endpoint is that transactions stay on the device.
  ok('fields outside the contract are dropped, not forwarded', () => {
    const clean = sanitiseFigures({
      ...VALID,
      transactions: [{ title: 'Therapy session', amount: 4000 }],
      notes: 'private note',
      uid: 'someone-else'
    });

    assert.strictEqual(clean.transactions, undefined, 'transaction rows must not survive');
    assert.strictEqual(clean.notes, undefined);
    assert.strictEqual(clean.uid, undefined);
    assert.deepStrictEqual(
      Object.keys(clean).sort(),
      ['averagePerDay', 'currency', 'highlights', 'periodLabel', 'previousExpense',
        'topCategories', 'topMerchants', 'totalExpense', 'totalIncome', 'transactionCount']
    );
  });

  // Merchant names come out of parsed documents, so they are attacker-influenced
  // text heading into a prompt. Length is the part we can bound here.
  ok('long names are truncated rather than passed through whole', () => {
    const clean = sanitiseFigures({
      ...VALID,
      topMerchants: [{ name: 'x'.repeat(500), amount: 10, count: 1 }]
    });

    assert.strictEqual(clean.topMerchants[0].name.length, 60);
  });

  ok('whitespace around names is trimmed', () => {
    const clean = sanitiseFigures({ ...VALID, periodLabel: '  August 2026  ' });
    assert.strictEqual(clean.periodLabel, 'August 2026');
  });

  ok('absent optional fields become zero rather than undefined', () => {
    const clean = sanitiseFigures({
      periodLabel: 'August 2026',
      currency: 'INR',
      totalExpense: 100
    });

    assert.strictEqual(clean.totalIncome, 0);
    assert.strictEqual(clean.transactionCount, 0);
    assert.deepStrictEqual(clean.topMerchants, []);
    assert.deepStrictEqual(clean.highlights, []);
  });

  rejects('a missing body', null, /Missing summary figures/);
  rejects('an array body', [], /Missing summary figures/);
  rejects('a missing period label', { ...VALID, periodLabel: '' }, /required/);
  rejects('a non-string label', { ...VALID, periodLabel: 42 }, /must be a string/);
  rejects('zero spending', { ...VALID, totalExpense: 0 }, /greater than zero/);
  rejects('a negative amount', { ...VALID, totalIncome: -5 }, /non-negative/);
  rejects('a NaN amount', { ...VALID, averagePerDay: 'abc' }, /non-negative/);
  rejects(
    'an oversized list',
    { ...VALID, topMerchants: new Array(20).fill({ name: 'A', amount: 1 }) },
    /at most 8 items/
  );
  rejects('a non-array list', { ...VALID, highlights: 'not a list' }, /must be an array/);

  console.log('\nnarrative generation');

  calls.length = 0;
  queue = [reply({
    headline: 'A heavier month than usual',
    narrative: 'You spent ₹40,000 across 62 transactions.',
    suggestions: ['Food is your largest category.', 'b', 'c', 'd', 'e']
  })];
  const result = await narrateSpending(sanitiseFigures(VALID));

  ok('the summary is returned with suggestions capped', () => {
    assert.strictEqual(result.headline, 'A heavier month than usual');
    assert.ok(result.narrative.includes('62 transactions'));
    assert.strictEqual(result.suggestions.length, 3, 'more than three is a list, not a summary');
  });

  ok('thinking tokens are billed as output', () => {
    assert.strictEqual(result.usage.outputTokens, 150); // 120 + 30
    assert.strictEqual(result.usage.inputTokens, 400);
  });

  ok('the request carries only the sanitised figures', () => {
    const sent = calls[0].contents[0].parts[0].text;
    assert.ok(sent.includes('August 2026'));
    assert.ok(!/Therapy/.test(sent), 'no transaction text should ever appear');
    assert.strictEqual(calls[0].config.responseMimeType, 'application/json');
  });

  calls.length = 0;
  queue = [reply({ headline: '', narrative: '', suggestions: null })];
  const empty = await narrateSpending(sanitiseFigures(VALID));

  ok('a malformed suggestion list degrades to empty rather than throwing', () => {
    assert.deepStrictEqual(empty.suggestions, []);
    assert.strictEqual(empty.narrative, '');
  });

  calls.length = 0;
  queue = [Object.assign(
    new Error('{"error":{"code":404,"message":"model gone","status":"NOT_FOUND"}}'),
    { status: 404 }
  )];

  try {
    await narrateSpending(sanitiseFigures(VALID));
    throw new Error('expected a rejection');
  } catch (error) {
    // Same reasoning as extraction: a forwarded 404 reads as a missing route.
    assert.strictEqual(error.status, 502, 'provider failure must not masquerade as a 404');
    assert.ok(/model gone/.test(error.message));
    console.log('  ok  a provider failure is reported as 502');
  }

  console.log('\nall narrative tests passed');
})().catch((error) => {
  console.error('\nFAILED:', error.message);
  process.exit(1);
});
