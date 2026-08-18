/**
 * No personalised investment suggestion without a confirmed risk profile.
 *
 * This is the constraint with a regulator behind it as well as a reason. SEBI's
 * framework for investment advisers treats risk profiling and a suitability
 * assessment as prerequisites to advising a particular person — not as
 * paperwork that follows the advice. An app that suggests where somebody should
 * put their money without knowing what they can afford to lose is doing the
 * thing that framework exists to prevent, whatever disclaimer sits underneath.
 *
 * A profile that was started and never confirmed does not count. That is the
 * whole point of the gate built in Stage 4: answers the user never agreed to are
 * a draft, and treating a draft as consent is how an unsuitable recommendation
 * arrives looking exactly like a suitable one.
 *
 * A stale profile is refused too. Circumstances change — a child, a job loss, a
 * mortgage — and a profile from three years ago describes somebody who may no
 * longer exist. Re-asking is cheap; being wrong about this is not.
 */
module.exports = {
  id: 'REQUIRE_RISK_PROFILE',
  version: '1.0.0',

  /**
   * Only candidates that recommend where money should be invested.
   *
   * Deliberately narrow. Telling somebody to build a reserve, pay down a loan or
   * fund a goal needs no risk profile — those are arithmetic about their own
   * cash, not a view about markets.
   */
  appliesTo: (candidate) => Boolean(candidate.isInvestmentRecommendation),

  evaluate(candidate, context) {
    const { riskProfile } = context;

    if (!riskProfile || !riskProfile.known) {
      return {
        passed: false,
        reason: 'NO_CONFIRMED_PROFILE',
        detail:
          riskProfile?.reason ??
          'No confirmed risk profile, so no suggestion is made about where to ' +
          'invest. Answering the risk questions would let the app say more.'
      };
    }

    if (riskProfile.isStale) {
      return {
        passed: false,
        reason: 'PROFILE_STALE',
        detail:
          'Your risk profile is over two years old. Circumstances change, and a ' +
          'suggestion about investments should rest on a current one.'
      };
    }

    // Capacity is the one that binds. Somebody willing to take more risk than
    // their finances can absorb should be measured against what they can absorb,
    // and a profile with no capacity recorded cannot support that judgement.
    if (!riskProfile.capacity) {
      return {
        passed: false,
        reason: 'NO_CAPACITY_RECORDED',
        detail:
          'Your profile records how much risk you are comfortable with but not ' +
          'how much your finances could absorb, which is the one that decides.'
      };
    }

    return {
      passed: true,
      reason: null,
      detail: `Measured against a confirmed capacity of ${riskProfile.capacity}.`
    };
  }
};
