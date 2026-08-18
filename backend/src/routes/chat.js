const express = require('express');
const crypto = require('crypto');
const { pool } = require('../config/db');
const { requireFirebaseAuth } = require('../middleware/auth');
// The history read is not model-backed, so it takes the ordinary sync limiter
// rather than the tighter model one — but it does read a conversation about
// somebody's finances, so it is not unlimited either.
const { modelLimiter, syncLimiter } = require('../middleware/rateLimit');
const { buildAssessmentContext, presentable } = require('../services/assessmentContext');
const { writeTrace } = require('../services/decisionTraceStore');
const { countCallsThisMonth, recordUsage, FEATURE } = require('../services/modelUsage');
const { retrieve, refusalFor } = require('../services/knowledgeRetrieval');
const { recommend, POLICY_VERSION } = require('../engine/recommendationEngine');
const { buildValueRegistry } = require('../engine/explanationSlotFiller');
const { constraintVersions } = require('../engine/constraintEngine');
const {
  classifyIntent, composeReply, sanitiseQuestion, sanitiseHistory, INTENT, MODEL
} = require('../services/chatOrchestrator');

const router = express.Router();

/**
 * Chat's own budget, independent of extraction's.
 *
 * A user who imports a year of statements should still be able to ask a
 * question, and a long conversation should not block their next import.
 */
const CHAT_MONTHLY_CAP = Number(process.env.CHAT_MONTHLY_CAP || 300);

/**
 * POST /chat
 *
 * A question about the user's own finances, answered from the engine's figures.
 *
 * The order matters and is the whole design: the engine computes first, the
 * model words the result second, and the numeric gate validates third. A model
 * that invents a figure has nowhere to put it, because the only numbers that
 * reach the user are ones the server substituted from what it computed.
 *
 * Every exchange writes a decision trace. "Why did it say that" has to resolve
 * all the way back to the snapshot and the constraint verdicts, or the
 * separation between explaining and deciding is unverifiable.
 */
router.post('/', requireFirebaseAuth, modelLimiter, async (req, res, next) => {
  try {
    const uid = req.user.uid;
    const now = Date.now();

    const question = sanitiseQuestion(req.body?.message);
    if (question.length === 0) throw badRequest('Ask a question.');

    const history = sanitiseHistory(req.body?.history);
    const conversationId = validConversationId(req.body?.conversationId) ?? crypto.randomUUID();

    const usedThisMonth = await countCallsThisMonth(uid, FEATURE.CHAT);
    if (usedThisMonth >= CHAT_MONTHLY_CAP) {
      const error = new Error(
        `Monthly limit of ${CHAT_MONTHLY_CAP} questions reached. It resets at the ` +
        'start of next month.'
      );
      error.status = 429;
      throw error;
    }

    const classification = await classifyIntent(question);
    await recordUsage({ uid, feature: FEATURE.CHAT, model: MODEL, usage: classification.usage });

    // Refused before any assessment is built. There is nothing to compute for a
    // question this product will not answer, and building the context anyway
    // would put the user's finances into a request that exists only to decline.
    if (classification.intent === INTENT.OUT_OF_SCOPE) {
      return res.json(await declineOutOfScope({
        uid, conversationId, question, classification, now
      }));
    }

    // A question about what a rule *is* rather than about their figures. The
    // answer lives in a document, so the engine has nothing to contribute and
    // the user's finances never enter the request.
    if (classification.intent === INTENT.KNOWLEDGE) {
      return res.json(await answerFromKnowledge({
        uid, conversationId, question, classification, now
      }));
    }

    const context = await buildAssessmentContext({ uid, query: req.body ?? {}, now });
    const recommendation = recommend(context);
    const registry = buildValueRegistry(context);

    const composed = await composeReply({
      question,
      intent: classification.intent,
      registry,
      engineSummary: engineSummaryFor(context, recommendation),
      history
    });
    await recordUsage({ uid, feature: FEATURE.CHAT, model: composed.model, usage: composed.usage });

    // The gate refused the model's wording. The engine answers instead — plainer,
    // and never wrong, which is the trade this whole mechanism exists to make.
    const paragraphs = composed.rejected
      ? fallbackAnswer(context, recommendation)
      : composed.paragraphs;

    const traceId = await writeTrace({
      uid,
      userQuestion: question,
      intent: classification.intent,
      snapshotId: context.snapshotId,
      policyVersion: POLICY_VERSION,
      constraintVersions: constraintVersions(),
      consentState: { chatEnabled: true, historyTurnsSent: history.length },
      modelProvider: 'google',
      modelName: composed.model,
      selectedCandidateId: recommendation.selected?.id ?? null,
      rejectedCandidateIds: recommendation.rejected.map((candidate) => candidate.id),
      payload: {
        intentReason: classification.reason,
        numericGate: {
          rejected: composed.rejected,
          reason: composed.rejectionReason ?? null,
          unknownReferences: composed.unknownReferences ?? []
        },
        referencesUsed: paragraphs.flatMap((paragraph) => paragraph.references ?? []),
        selected: recommendation.selected ? presentable(recommendation.selected) : null,
        rejected: recommendation.rejected.map(presentable),
        caveats: recommendation.caveats
      }
    });

    await storeExchange({
      uid, conversationId, question, paragraphs, traceId,
      intent: classification.intent,
      rejectedReason: composed.rejected ? composed.rejectionReason : null,
      now
    });

    return res.json({
      conversationId,
      traceId,
      snapshotId: context.snapshotId,
      intent: classification.intent,
      paragraphs: paragraphs.map((paragraph) => paragraph.text),
      suggestedFollowUps: composed.suggestedFollowUps ?? [],
      // Surfaced rather than buried. An answer built on data the device has not
      // finished syncing is a different answer, and hiding that would make the
      // confident tone of the reply misleading.
      dataQuality: {
        confidence: context.dataQuality.confidence,
        caveats: context.dataQuality.caveats
      },
      wordingFallback: composed.rejected,
      callsUsedThisMonth: usedThisMonth + 2,
      monthlyCallCap: CHAT_MONTHLY_CAP
    });
  } catch (error) {
    return next(error);
  }
});

/** GET /chat/:conversationId — the exchange so far, owner-scoped. */
router.get('/:conversationId', requireFirebaseAuth, syncLimiter, async (req, res, next) => {
  try {
    const [rows] = await pool.execute(
      `SELECT role, content, intent, decision_trace_id AS decisionTraceId,
              rejected_reason AS rejectedReason, created_at AS createdAt
         FROM chat_messages
        WHERE uid = ? AND conversation_id = ?
        ORDER BY created_at ASC, id ASC
        LIMIT 200`,
      [req.user.uid, req.params.conversationId]
    );

    return res.json({ conversationId: req.params.conversationId, messages: rows });
  } catch (error) {
    return next(error);
  }
});

/**
 * What the model is shown.
 *
 * Aggregates and statuses only. There is no path from this object to an
 * individual transaction or a merchant name — not because the prompt asks for
 * restraint, but because those values are never assembled into the request.
 */
function engineSummaryFor(context, recommendation) {
  return {
    currency: context.currency,
    period: context.period,
    dataQuality: {
      confidence: context.dataQuality.confidence,
      caveats: context.dataQuality.caveats,
      readiness: context.dataQuality.readiness
    },
    cashFlow: {
      monthsObserved: context.cashFlow.monthsObserved,
      incomeStability: context.cashFlow.income.stability.band,
      trendIsMeaningful: context.cashFlow.trendIsMeaningful,
      caveats: context.cashFlow.caveats
    },
    reserve: {
      status: context.emergencyFund.status,
      targetMonths: context.emergencyFund.target.months,
      caveats: context.emergencyFund.caveats
    },
    debt: { load: context.debt.debtLoad, caveats: context.debt.caveats },
    protection: { overall: context.protection.overall, caveats: context.protection.caveats },
    portfolio: { status: context.portfolio.status, caveats: context.portfolio.caveats },
    riskProfile: { known: context.riskProfile.known, reason: context.riskProfile.reason },
    goals: context.goals.goals.map((goal) => ({
      localId: goal.localId,
      type: goal.type,
      feasibility: goal.feasibility,
      monthsRemaining: goal.monthsRemaining,
      reasons: goal.reasons
    })),
    recommendation: {
      selected: recommendation.selected
        ? { id: recommendation.selected.id, type: recommendation.selected.type,
          title: recommendation.selected.title, rationale: recommendation.selected.rationale }
        : null,
      rejected: recommendation.rejected.map((candidate) => ({
        id: candidate.id, type: candidate.type, reasons: candidate.rejectionReasons
      })),
      caveats: recommendation.caveats
    }
  };
}

/**
 * The engine's own words, used when the gate refuses the model's.
 *
 * Written from the recommendation rather than the registry, so it is always
 * correct by construction. Plainer than a composed reply and never wrong, which
 * is the right way round for a fallback.
 */
function fallbackAnswer(context, recommendation) {
  const paragraphs = [];

  if (recommendation.selected) {
    paragraphs.push({
      text: `${recommendation.selected.title}. ${recommendation.selected.rationale}`,
      references: []
    });
  } else {
    paragraphs.push({
      text: 'Nothing stands out as needing attention from what you have recorded.',
      references: []
    });
  }

  if (context.dataQuality.caveats.length > 0) {
    paragraphs.push({ text: context.dataQuality.caveats[0], references: [] });
  }

  paragraphs.push({
    text:
      'That answer is written straight from the figures rather than in the ' +
      'assistant\'s words, because the wording it produced referred to a value ' +
      'that does not exist.',
    references: []
  });

  return paragraphs;
}

/**
 * Declines a question outside what this product answers.
 *
 * Traced like any other exchange. A refusal is a decision, and a log that records
 * only the questions that got answers cannot show what the boundary actually did.
 */
async function declineOutOfScope({ uid, conversationId, question, classification, now }) {
  const paragraphs = [{
    text:
      'That is outside what this app does. It works out what your own figures ' +
      'show — what you earn, spend, owe and have saved — and does not recommend ' +
      'specific shares, funds or policies.',
    references: []
  }, {
    text:
      'For a recommendation about a particular investment, a SEBI-registered ' +
      'investment adviser can take your full circumstances into account.',
    references: []
  }];

  const traceId = await writeTrace({
    uid,
    userQuestion: question,
    intent: INTENT.OUT_OF_SCOPE,
    snapshotId: null,
    policyVersion: POLICY_VERSION,
    constraintVersions: constraintVersions(),
    consentState: { chatEnabled: true, historyTurnsSent: 0 },
    modelProvider: 'google',
    modelName: MODEL,
    selectedCandidateId: null,
    rejectedCandidateIds: [],
    payload: { intentReason: classification.reason, declined: true }
  });

  await storeExchange({
    uid, conversationId, question, paragraphs, traceId,
    intent: INTENT.OUT_OF_SCOPE, rejectedReason: null, now
  });

  return {
    conversationId,
    traceId,
    snapshotId: null,
    intent: INTENT.OUT_OF_SCOPE,
    paragraphs: paragraphs.map((paragraph) => paragraph.text),
    suggestedFollowUps: [],
    dataQuality: null,
    wordingFallback: false
  };
}

/**
 * Answers a rules question from stored, reviewed text — or says there is none.
 *
 * The model is not involved in composing this. A passage from a regulator, shown
 * as written with its citation, is more useful and far safer than the same
 * passage paraphrased: paraphrasing is where a limit becomes "around" a limit
 * and a requirement becomes a suggestion.
 *
 * When nothing current is on file, the refusal is explicit about which gate
 * refused — "overdue a check" and "nothing on this" are different facts, and the
 * first is an operational problem somebody should see.
 */
async function answerFromKnowledge({ uid, conversationId, question, classification, now }) {
  const result = await retrieve({ question, now });

  const paragraphs = result.found
    ? [
      ...result.snippets.map((snippet) => ({
        text: snippet.text,
        references: [],
        citation: {
          publisher: snippet.source.publisher,
          title: snippet.source.title,
          url: snippet.source.url
        }
      })),
      {
        text:
          'That is quoted from the source shown rather than summarised, so you ' +
          'can check it. It is general information, not advice about your ' +
          'situation.',
        references: []
      }
    ]
    : [{
      text:
        `${refusalFor(result.reason)} Rather than answer from memory, which ` +
        'could be out of date or wrong, the app would rather say so.',
      references: []
    }];

  const traceId = await writeTrace({
    uid,
    userQuestion: question,
    intent: INTENT.KNOWLEDGE,
    snapshotId: null,
    policyVersion: POLICY_VERSION,
    constraintVersions: constraintVersions(),
    consentState: { chatEnabled: true, historyTurnsSent: 0 },
    modelProvider: 'google',
    modelName: MODEL,
    selectedCandidateId: null,
    rejectedCandidateIds: [],
    payload: {
      intentReason: classification.reason,
      knowledge: {
        found: result.found,
        reason: result.reason,
        // Which statements were relied on, so a citation can be checked later
        // against what was actually claimed.
        claimKeys: result.claims.map((claim) => claim.claimKey),
        sourceIds: result.snippets.map((snippet) => snippet.source.sourceId)
      }
    }
  });

  await storeExchange({
    uid, conversationId, question, paragraphs, traceId,
    intent: INTENT.KNOWLEDGE, rejectedReason: null, now
  });

  return {
    conversationId,
    traceId,
    snapshotId: null,
    intent: INTENT.KNOWLEDGE,
    paragraphs: paragraphs.map((paragraph) => paragraph.text),
    citations: result.snippets.map((snippet) => ({
      publisher: snippet.source.publisher,
      title: snippet.source.title,
      url: snippet.source.url,
      reviewer: snippet.source.reviewer
    })),
    claims: result.claims.map((claim) => ({
      key: claim.claimKey,
      text: claim.claimText,
      publisher: claim.publisher,
      url: claim.url
    })),
    suggestedFollowUps: [],
    dataQuality: null,
    wordingFallback: false
  };
}

async function storeExchange({ uid, conversationId, question, paragraphs, traceId, intent, rejectedReason, now }) {
  await pool.execute(
    `INSERT INTO chat_messages (uid, conversation_id, role, content, intent, decision_trace_id, created_at)
     VALUES (?, ?, 'USER', ?, ?, ?, ?)`,
    [uid, conversationId, question, intent, traceId, now]
  );

  await pool.execute(
    `INSERT INTO chat_messages
      (uid, conversation_id, role, content, decision_trace_id, rejected_reason, created_at)
     VALUES (?, ?, 'ASSISTANT', ?, ?, ?, ?)`,
    // Stored filled rather than as templates: what the user saw is what a later
    // reader needs, and the template is an implementation detail.
    [uid, conversationId, paragraphs.map((p) => p.text).join('\n\n'), traceId, rejectedReason, now + 1]
  );
}

const validConversationId = (value) =>
  typeof value === 'string' && /^[0-9a-f-]{36}$/i.test(value) ? value : null;

function badRequest(message) {
  const error = new Error(message);
  error.status = 400;
  return error;
}

module.exports = router;
