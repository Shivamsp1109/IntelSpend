/**
 * Escalation logic for visionExtraction.
 *
 * Run with: npm test
 *
 * The Gemini client is stubbed, so this exercises the routing decisions without
 * spending anything. What matters here is money: every escalation is a second
 * billed call, so "when do we escalate" and "which answer wins" both need to be
 * pinned down.
 */
const assert = require('assert');
const Module = require('module');

const calls = [];
let queue = [];

// Stub @google/genai before the service requires it. `Type` must be re-exported
// because the response schema is built from it at module load.
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
            calls.push(params.model);
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
const { extractFromImage } = require('../src/services/visionExtraction.js');

/** Mirrors the real GenerateContentResponse shape the service reads. */
function reply(transactions, finishReason = 'STOP') {
  return {
    candidates: [{ finishReason }],
    text: JSON.stringify({ documentType: 'RECEIPT', transactions }),
    usageMetadata: {
      promptTokenCount: 1800,
      candidatesTokenCount: 200,
      thoughtsTokenCount: 50
    }
  };
}

function txn(confidence, amount = 100) {
  return {
    amount,
    currency: 'INR',
    date: '2026-08-11',
    merchant: 'Test Merchant',
    direction: 'DEBIT',
    category: 'Food',
    confidence,
    amountSource: String(amount)
  };
}

const IMAGE = { base64: 'AAAA', mediaType: 'image/jpeg' };

async function run(name, stubs, assertions) {
  calls.length = 0;
  queue = stubs;
  const result = await extractFromImage(IMAGE);
  assertions(result, calls);
  console.log(`  ok  ${name}`);
}

async function expectFailure(name, stubs, assertion) {
  calls.length = 0;
  queue = stubs;
  try {
    await extractFromImage(IMAGE);
    throw new Error('expected a rejection');
  } catch (error) {
    assertion(error);
    console.log(`  ok  ${name}`);
  }
}

(async () => {
  console.log('vision extraction escalation (gemini)');

  await run(
    'confident cheap result does not escalate',
    [reply([txn(0.95)])],
    (result, models) => {
      assert.strictEqual(models.length, 1, 'should call exactly one model');
      assert.strictEqual(models[0], 'gemini-3.1-flash-lite');
      assert.strictEqual(result.escalated, false);
      assert.strictEqual(result.attempts.length, 1, 'bills one call');
    }
  );

  await run(
    'low confidence escalates to the stronger model and prefers it',
    [reply([txn(0.4)]), reply([txn(0.9, 250)])],
    (result, models) => {
      assert.deepStrictEqual(models, ['gemini-3.1-flash-lite', 'gemini-3-flash']);
      assert.strictEqual(result.escalated, true);
      assert.strictEqual(result.transactions[0].amount, 250);
      assert.strictEqual(result.attempts.length, 2, 'bills both calls');
    }
  );

  await run(
    'empty cheap result escalates',
    [reply([]), reply([txn(0.85)])],
    (result, models) => {
      assert.strictEqual(models.length, 2);
      assert.strictEqual(result.escalated, true);
      assert.strictEqual(result.transactions.length, 1);
    }
  );

  await run(
    'escalation returning nothing keeps the cheap partial read',
    [reply([txn(0.4, 99)]), reply([])],
    (result) => {
      assert.strictEqual(result.escalated, false, 'empty fallback must not win');
      assert.strictEqual(result.transactions[0].amount, 99);
      assert.strictEqual(result.attempts.length, 2, 'still bills both attempts');
    }
  );

  await run(
    'escalation failure falls back to the cheap result',
    [reply([txn(0.3, 42)]), new Error('upstream 503')],
    (result) => {
      assert.strictEqual(result.transactions[0].amount, 42, 'must not surface an error');
      assert.strictEqual(result.attempts.length, 1, 'failed attempt is not billed');
    }
  );

  await run(
    'escalation keeps the stronger read when confidence improves',
    [reply([txn(0.5, 10)]), reply([txn(0.5, 20)])],
    (result) => {
      assert.strictEqual(result.escalated, true, 'equal confidence prefers the stronger model');
      assert.strictEqual(result.transactions[0].amount, 20);
    }
  );

  await run(
    'lowest confidence in a batch drives the decision',
    [reply([txn(0.95), txn(0.2)]), reply([txn(0.9), txn(0.9)])],
    (result, models) => {
      assert.strictEqual(models.length, 2, 'one weak row escalates the whole page');
      assert.strictEqual(result.escalated, true);
    }
  );

  await run(
    'thinking tokens are billed as output',
    [reply([txn(0.95)])],
    (result) => {
      // candidatesTokenCount 200 + thoughtsTokenCount 50
      assert.strictEqual(result.attempts[0].usage.outputTokens, 250);
      assert.strictEqual(result.attempts[0].usage.inputTokens, 1800);
    }
  );

  await expectFailure(
    'safety block surfaces a clear error, not a JSON parse crash',
    [reply([], 'PROHIBITED_CONTENT')],
    (error) => {
      assert.strictEqual(error.status, 422);
      assert.ok(/PROHIBITED_CONTENT/.test(error.message));
    }
  );

  await expectFailure(
    'truncated output reports too many transactions',
    [reply([], 'MAX_TOKENS')],
    (error) => assert.strictEqual(error.status, 413)
  );

  // Regression: a provider 404 used to reach the client as a 404, which reads as
  // "the /extract route is missing" and sends you debugging the wrong layer.
  await expectFailure(
    'provider 404 is reported as 502, not forwarded as 404',
    [Object.assign(new Error(
      '{"error":{"code":404,"message":"This model models/foo is no longer available to new users.","status":"NOT_FOUND"}}'
    ), { status: 404, name: 'ApiError' })],
    (error) => {
      assert.strictEqual(error.status, 502, 'must not masquerade as a missing route');
      assert.ok(/no longer available to new users/.test(error.message), 'keeps the real cause');
      assert.ok(/gemini-3\.1-flash-lite/.test(error.message), 'names the failing model');
    }
  );

  console.log('\nall escalation tests passed');
})().catch((error) => {
  console.error('\nFAILED:', error.message);
  process.exit(1);
});
