const express = require('express');
const { requireFirebaseAuth } = require('../middleware/auth');
const { syncLimiter } = require('../middleware/rateLimit');
const { recommend, POLICY_VERSION } = require('../engine/recommendationEngine');
const { writeTrace } = require('../services/decisionTraceStore');
const { buildAssessmentContext, presentable } = require('../services/assessmentContext');

const router = express.Router();

/**
 * POST /recommendations
 *
 * What the engine suggests, and everything it refused to suggest.
 *
 * Deterministic end to end. No model is called here and none will be: when the
 * assistant arrives it will explain this output rather than produce it, and the
 * fact that this endpoint already works — tested, reproducible, with its
 * refusals recorded — is what makes that division real rather than aspirational.
 *
 * Every run writes a decision trace. That is not optional and not sampled: a
 * recommendation nobody can later explain is one nobody can later check.
 */
router.post('/', requireFirebaseAuth, syncLimiter, async (req, res, next) => {
  try {
    const uid = req.user.uid;
    const now = Date.now();

    const context = await buildAssessmentContext({
      uid,
      query: req.body ?? {},
      now
    });

    const result = recommend(context);

    const traceId = await writeTrace({
      uid,
      // Null here. A question only exists once there is a chat, and recording an
      // empty string would make a trace look like it answered something.
      userQuestion: null,
      intent: 'RECOMMENDATION_RUN',
      snapshotId: context.snapshotId,
      policyVersion: POLICY_VERSION,
      constraintVersions: result.constraintVersions,
      selectedCandidateId: result.selected?.id ?? null,
      rejectedCandidateIds: result.rejected.map((candidate) => candidate.id),
      payload: {
        // The verdicts, not just the outcomes: which rule ran, what version, and
        // what it said. Without this a trace records that something was rejected
        // and not why, which explains nothing.
        selected: result.selected ? presentable(result.selected) : null,
        alternatives: result.alternatives.map(presentable),
        rejected: result.rejected.map(presentable),
        dataQuality: context.dataQuality,
        caveats: result.caveats
      }
    });

    return res.json({
      traceId,
      // The snapshot the whole thing rested on, so a caller can fetch the exact
      // figures rather than re-deriving state that has since moved.
      snapshotId: context.snapshotId,
      policyVersion: result.policyVersion,
      constraintVersions: result.constraintVersions,
      currency: result.currency,
      selected: result.selected ? presentable(result.selected) : null,
      alternatives: result.alternatives.map(presentable),
      // Returned, not hidden. Why something was withheld is often more useful
      // than what survived.
      rejected: result.rejected.map(presentable),
      caveats: result.caveats
    });
  } catch (error) {
    return next(error);
  }
});

module.exports = router;
