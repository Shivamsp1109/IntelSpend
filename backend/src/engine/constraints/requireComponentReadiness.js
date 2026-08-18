/**
 * Refuse a candidate whose supporting component is BLOCKED.
 *
 * The readiness model already knows which questions can be answered from what
 * has been recorded. This constraint is what stops a recommendation being made
 * anyway — because every engine below degrades gracefully, and gracefully
 * degraded figures still add up to something that reads like advice.
 *
 * A DEGRADED component is allowed through with its caveat attached. Stale data
 * is worth less, not worthless, and refusing everything on four-month-old
 * spending would make the app useless to somebody who imports quarterly. BLOCKED
 * means the thing the answer depends on is simply absent, and there is no
 * version of that which supports a suggestion.
 */
const { READINESS } = require('../readiness');

module.exports = {
  id: 'REQUIRE_COMPONENT_READINESS',
  version: '1.0.0',

  /** Candidates declare which components they rest on. */
  appliesTo: (candidate) => Array.isArray(candidate.dependsOn) && candidate.dependsOn.length > 0,

  evaluate(candidate, context) {
    const readiness = context.dataQuality?.readiness;

    if (!readiness) {
      return {
        passed: false,
        reason: 'READINESS_UNKNOWN',
        detail: 'No data-quality assessment is available, so nothing is suggested.'
      };
    }

    const blocked = candidate.dependsOn.filter(
      (component) => readiness[component]?.status === READINESS.BLOCKED
    );

    if (blocked.length > 0) {
      const missing = [...new Set(
        blocked.flatMap((component) => readiness[component]?.missing ?? [])
      )];

      return {
        passed: false,
        reason: 'COMPONENT_BLOCKED',
        detail:
          `This rests on ${blocked.join(', ')}, which cannot be worked out ` +
          `without ${missing.join(', ') || 'more information'}.`
      };
    }

    const degraded = candidate.dependsOn.filter(
      (component) => readiness[component]?.status === READINESS.DEGRADED
    );

    return {
      passed: true,
      reason: null,
      // Carried through so the recommendation can repeat it rather than
      // presenting a figure built on old data as though it were current.
      detail: degraded.length > 0
        ? `Based on data that may be out of date for ${degraded.join(', ')}.`
        : null
    };
  }
};
