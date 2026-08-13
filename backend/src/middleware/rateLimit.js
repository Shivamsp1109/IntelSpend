const rateLimit = require('express-rate-limit');

/**
 * Rate limiting, in two layers.
 *
 * The outer layer keys on IP address and exists only to stop an unauthenticated
 * flood from reaching the auth check, which itself costs a network round trip to
 * Firebase. It is deliberately generous.
 *
 * The real limits key on the authenticated user, because IP is close to
 * meaningless for a mobile app. Indian carriers put large numbers of subscribers
 * behind a handful of NAT addresses, so an IP budget is shared by strangers —
 * one busy client can lock out everyone on the same carrier gateway. The same
 * user moving from wifi to mobile data also gets a fresh budget for free. Keying
 * on the Firebase uid gives every account its own allowance regardless of where
 * it connects from.
 *
 * These limiters must be mounted after `requireFirebaseAuth`, since they read
 * `req.user.uid`.
 */

/** Shared by both layers so responses are consistent. */
function tooManyRequests(retryAfterHint) {
  return (req, res) => {
    res.status(429).json({
      error: `Too many requests. Try again in ${retryAfterHint}.`
    });
  };
}

function perUser({ windowMs, max, retryAfterHint }) {
  return rateLimit({
    windowMs,
    max,
    standardHeaders: true,
    legacyHeaders: false,
    // Falls back to IP only if this somehow runs before auth, so a
    // misconfiguration degrades to the old behaviour rather than to no limit
    // at all.
    keyGenerator: (req) => req.user?.uid || req.ip,
    handler: tooManyRequests(retryAfterHint)
  });
}

/**
 * Front door, before authentication.
 *
 * Wide enough that no real client will notice, narrow enough that an unattended
 * script cannot make us verify tokens all day.
 */
const publicLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  max: Number(process.env.RATE_LIMIT_PUBLIC_MAX || 600),
  standardHeaders: true,
  legacyHeaders: false,
  handler: tooManyRequests('a few minutes')
});

/**
 * Ordinary data sync. Generous because a first sync after an import can push a
 * few hundred rows in a burst, one request each.
 */
const syncLimiter = perUser({
  windowMs: 15 * 60 * 1000,
  max: Number(process.env.RATE_LIMIT_SYNC_MAX || 400),
  retryAfterHint: 'a few minutes'
});

/**
 * Model-backed routes, which cost real money per call.
 *
 * The monthly cap in the extract router bounds the bill over a month; this
 * bounds how fast it can be spent. Without it, a client stuck in a retry loop
 * burns the entire monthly allowance in a couple of minutes, and the user finds
 * out when the feature stops working rather than when it goes wrong.
 */
const modelLimiter = perUser({
  windowMs: 60 * 1000,
  max: Number(process.env.RATE_LIMIT_MODEL_MAX || 12),
  retryAfterHint: 'a minute'
});

module.exports = {
  publicLimiter,
  syncLimiter,
  modelLimiter
};
