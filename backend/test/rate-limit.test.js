/**
 * Per-user rate limiting.
 *
 * Run with: npm test
 *
 * The property that matters is isolation: one account hitting its ceiling must
 * not affect anybody else. That is the whole reason these limiters key on the
 * Firebase uid rather than the IP address — Indian carriers put many
 * subscribers behind a handful of NAT addresses, so an IP-keyed budget is
 * shared between strangers.
 */
const assert = require('assert');

process.env.RATE_LIMIT_MODEL_MAX = '3';
process.env.RATE_LIMIT_SYNC_MAX = '5';

const { modelLimiter, syncLimiter, publicLimiter } = require('../src/middleware/rateLimit');

/**
 * Minimal req/res pair, enough for express-rate-limit to do its work.
 *
 * `app.get` has to be here: the default key generator asks Express for the
 * trust-proxy setting, and without it the middleware fails inside its own
 * validation rather than doing anything meaningful.
 */
function request(uid, ip = '203.0.113.7') {
  return {
    user: uid ? { uid } : undefined,
    ip,
    ips: [],
    headers: {},
    method: 'POST',
    url: '/',
    app: { get: () => false }
  };
}

function response() {
  const res = {
    statusCode: 200,
    body: undefined,
    headersSent: false,
    setHeader() {},
    getHeader() {},
    removeHeader() {},
    status(code) {
      this.statusCode = code;
      return this;
    },
    json(payload) {
      this.body = payload;
      this.headersSent = true;
      return this;
    }
  };
  return res;
}

/**
 * Drives the middleware once and reports whether the call was let through.
 *
 * Rejects if the middleware passes an error to `next`. Treating that as success
 * is exactly how an earlier version of this file reported green while the
 * limiter was in fact throwing on every call.
 */
function send(limiter, req) {
  return new Promise((resolve, reject) => {
    const res = response();

    limiter(req, res, (error) => {
      if (error) reject(error);
      else resolve({ allowed: true, res });
    });

    // The store lookup is async, so give the queue a turn before concluding
    // the request was blocked.
    setImmediate(() => {
      if (res.headersSent) resolve({ allowed: false, res });
      else if (!res.headersSent) {
        setImmediate(() => {
          if (res.headersSent) resolve({ allowed: false, res });
          else reject(new Error('The limiter neither continued nor answered.'));
        });
      }
    });
  });
}

async function sendMany(limiter, req, times) {
  const results = [];
  for (let i = 0; i < times; i++) {
    results.push(await send(limiter, req));
  }
  return results;
}

(async () => {
  console.log('per-user rate limiting');

  {
    const results = await sendMany(modelLimiter, request('user-a'), 4);
    assert.deepStrictEqual(
      results.map((r) => r.allowed),
      [true, true, true, false],
      'the fourth call past a limit of 3 must be refused'
    );
    assert.strictEqual(results[3].res.statusCode, 429);
    assert.ok(/Try again/.test(results[3].res.body.error), 'the refusal should say what to do');
    console.log('  ok  a user is cut off at their own ceiling');
  }

  // The point of the whole exercise.
  {
    const exhausted = await sendMany(modelLimiter, request('user-b'), 4);
    assert.strictEqual(exhausted[3].allowed, false, 'user-b should be exhausted');

    const neighbour = await send(modelLimiter, request('user-c'));
    assert.strictEqual(
      neighbour.allowed,
      true,
      'a different account must be unaffected by user-b hitting the limit'
    );
    console.log('  ok  one account hitting its limit does not affect another');
  }

  // Same account, different network — the case IP-keyed limiting gets wrong in
  // the opposite direction, by handing out a fresh budget for free.
  {
    await sendMany(modelLimiter, request('user-d', '198.51.100.1'), 3);
    const fromAnotherNetwork = await send(modelLimiter, request('user-d', '198.51.100.99'));

    assert.strictEqual(
      fromAnotherNetwork.allowed,
      false,
      'switching networks must not reset the budget'
    );
    console.log('  ok  the budget follows the account, not the connection');
  }

  // Paid model calls are far scarcer than ordinary sync, which is the reason
  // they are separate limiters rather than one shared budget.
  {
    const sync = await sendMany(syncLimiter, request('user-e'), 5);
    assert.ok(sync.every((r) => r.allowed), 'sync should allow more than the model limit');

    const model = await sendMany(modelLimiter, request('user-e'), 4);
    assert.strictEqual(model[3].allowed, false, 'model calls stay tightly capped');
    console.log('  ok  sync and model budgets are independent');
  }

  // Requests arriving before authentication should still be counted rather than
  // sailing past unlimited.
  {
    const anonymous = await sendMany(modelLimiter, request(null, '192.0.2.50'), 4);
    assert.strictEqual(
      anonymous[3].allowed,
      false,
      'an unauthenticated caller must fall back to IP, not to no limit'
    );
    console.log('  ok  an unidentified caller falls back to an IP budget');
  }

  {
    const result = await send(publicLimiter, request(null, '192.0.2.77'));
    assert.strictEqual(result.allowed, true, 'the pre-auth layer should be generous');
    console.log('  ok  the pre-auth layer lets ordinary traffic through');
  }

  console.log('\nall rate limit tests passed');
})().catch((error) => {
  console.error('\nFAILED:', error.message);
  process.exit(1);
});
