const express = require('express');
const { pool } = require('../config/db');
const { requireFirebaseAuth, requireSameUser } = require('../middleware/auth');
const { syncLimiter } = require('../middleware/rateLimit');
const { ensureUserExists } = require('../services/users');
const { RISK_LEVEL, QUESTIONNAIRE_VERSION } = require('../engine/riskProfile');

const router = express.Router();

/** Answers are the user's own words about themselves; keep the payload bounded. */
const MAX_ANSWERS = 40;
const MAX_ANSWER_CHARS = 200;

/**
 * POST /risk-profile/sync
 *
 * One profile per user, replaced on re-assessment.
 *
 * `userConfirmed` is carried through as sent and never defaulted to true. The
 * whole point of the flag is that a profile the user has not explicitly agreed
 * to must not be readable downstream — silently promoting a draft would apply a
 * risk profile they never accepted to decisions about their money.
 */
router.post('/sync', requireFirebaseAuth, requireSameUser, syncLimiter, async (req, res, next) => {
  try {
    const profile = req.body;
    validateProfile(profile);

    await ensureUserExists(req.user.uid, req.user.email);

    await pool.execute(
      `INSERT INTO risk_assessments (
        uid, risk_tolerance, risk_capacity, risk_need,
        questionnaire_version, answers_json, assessment_date,
        limitations, user_confirmed, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON DUPLICATE KEY UPDATE
        risk_tolerance        = VALUES(risk_tolerance),
        risk_capacity         = VALUES(risk_capacity),
        risk_need             = VALUES(risk_need),
        questionnaire_version = VALUES(questionnaire_version),
        answers_json          = VALUES(answers_json),
        assessment_date       = VALUES(assessment_date),
        limitations           = VALUES(limitations),
        user_confirmed        = VALUES(user_confirmed),
        updated_at            = VALUES(updated_at)`,
      [
        req.user.uid,
        profile.riskTolerance || null,
        profile.riskCapacity || null,
        profile.riskNeed || null,
        profile.questionnaireVersion || QUESTIONNAIRE_VERSION,
        JSON.stringify(sanitiseAnswers(profile.answers)),
        Number(profile.assessmentDate) || Date.now(),
        JSON.stringify(sanitiseLimitations(profile.limitations)),
        profile.userConfirmed === true ? 1 : 0,
        Date.now()
      ]
    );

    return res.status(204).send();
  } catch (error) {
    return next(error);
  }
});

/** DELETE /risk-profile/sync — the user withdrew their profile. */
router.delete('/sync', requireFirebaseAuth, syncLimiter, async (req, res, next) => {
  try {
    await pool.execute('DELETE FROM risk_assessments WHERE uid = ?', [req.user.uid]);
    return res.status(204).send();
  } catch (error) {
    return next(error);
  }
});

/** GET /risk-profile — the current profile, or null. */
router.get('/', requireFirebaseAuth, syncLimiter, async (req, res, next) => {
  try {
    const [rows] = await pool.execute(
      `SELECT risk_tolerance        AS riskTolerance,
              risk_capacity         AS riskCapacity,
              risk_need             AS riskNeed,
              questionnaire_version AS questionnaireVersion,
              answers_json          AS answers,
              assessment_date       AS assessmentDate,
              limitations,
              user_confirmed        AS userConfirmed
         FROM risk_assessments
        WHERE uid = ?
        LIMIT 1`,
      [req.user.uid]
    );

    if (rows.length === 0) return res.json({ profile: null });

    const row = rows[0];
    return res.json({
      profile: {
        ...row,
        answers: parse(row.answers),
        limitations: parse(row.limitations),
        userConfirmed: row.userConfirmed === 1
      }
    });
  } catch (error) {
    return next(error);
  }
});

const parse = (value) => (typeof value === 'string' ? JSON.parse(value) : value);

/**
 * Rebuilt field by field with hard caps.
 *
 * These are free-text answers about somebody's own finances heading into stored
 * JSON that a later stage will put in front of a model. Spreading the client
 * body would carry whatever else was in it.
 */
function sanitiseAnswers(answers) {
  if (!Array.isArray(answers)) return [];
  return answers.slice(0, MAX_ANSWERS).map((answer) => ({
    questionId: String(answer?.questionId ?? '').slice(0, 60),
    answer: String(answer?.answer ?? '').slice(0, MAX_ANSWER_CHARS)
  }));
}

function sanitiseLimitations(limitations) {
  if (!Array.isArray(limitations)) return [];
  return limitations.slice(0, MAX_ANSWERS).map((note) => String(note).slice(0, MAX_ANSWER_CHARS));
}

function validateProfile(profile) {
  if (!profile) throw badRequest('Missing profile body.');
  if (!profile.uid) throw badRequest('Missing uid.');

  for (const field of ['riskTolerance', 'riskCapacity', 'riskNeed']) {
    const value = profile[field];
    if (value && !Object.values(RISK_LEVEL).includes(value)) {
      throw badRequest(`Unknown ${field}: ${value}`);
    }
  }

  // A confirmed profile must actually say something. Confirming an empty
  // questionnaire would create a profile that reads as known and answers nothing.
  if (profile.userConfirmed === true &&
      !profile.riskTolerance && !profile.riskCapacity && !profile.riskNeed) {
    throw badRequest('A confirmed risk profile must record at least one level.');
  }
}

function badRequest(message) {
  const error = new Error(message);
  error.status = 400;
  return error;
}

module.exports = router;
