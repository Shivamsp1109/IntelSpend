/**
 * Writing and redacting decision traces.
 *
 * A trace records why a recommendation was made: what was asked, what state it
 * rested on, which rules ran, what was rejected and what survived. It is the
 * mechanism by which "the engine decided, not the model" stops being a claim and
 * becomes something checkable after the fact.
 *
 * Redaction is a tombstone. When a user asks for a trace to be forgotten the row
 * stays with `redacted_at` set and its sensitive fields cleared, because a row
 * that simply vanishes makes the audit trail unreliable for everyone — including
 * for showing that a redaction happened at all. What is cleared is what
 * describes them; what remains is that a decision was made on a date.
 */
const crypto = require('crypto');
const { pool } = require('../config/db');
const { ENGINE_VERSION, PAYLOAD_SCHEMA_VERSION } = require('../engine/constants');

/** Default matches DECISION_TRACE_RETENTION_DAYS in .env.example. */
const DEFAULT_RETENTION_DAYS = 180;

const retentionDays = () => {
  const configured = Number(process.env.DECISION_TRACE_RETENTION_DAYS);
  return Number.isFinite(configured) && configured > 0 ? configured : DEFAULT_RETENTION_DAYS;
};

/**
 * Stores one trace and returns its id.
 *
 * `payload` carries the detail — calculations, constraint verdicts, candidates.
 * The observed state is deliberately *not* copied in: `snapshotId` points at the
 * row that already holds it whole, so there is one copy of the most sensitive
 * data in the product rather than two that can drift.
 */
async function writeTrace({
  uid,
  userQuestion = null,
  intent,
  snapshotId = null,
  policyVersion,
  constraintVersions,
  consentState = null,
  modelProvider = null,
  modelName = null,
  selectedCandidateId = null,
  rejectedCandidateIds = [],
  payload
}) {
  const traceId = crypto.randomUUID();

  await pool.execute(
    `INSERT INTO decision_traces (
      trace_id, uid, user_question, intent, snapshot_id,
      engine_version, policy_version, constraint_versions_json, payload_schema_version,
      consent_state, model_provider, model_name,
      selected_candidate_id, rejected_candidate_ids_json, payload, created_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    [
      traceId,
      uid,
      userQuestion,
      intent,
      snapshotId,
      ENGINE_VERSION,
      policyVersion,
      JSON.stringify(constraintVersions),
      PAYLOAD_SCHEMA_VERSION,
      consentState === null ? null : JSON.stringify(consentState),
      modelProvider,
      modelName,
      selectedCandidateId,
      JSON.stringify(rejectedCandidateIds),
      JSON.stringify(payload),
      Date.now()
    ]
  );

  return traceId;
}

/** Reads one trace, scoped to its owner. Returns null when absent. */
async function readTrace(traceId, uid) {
  const [rows] = await pool.execute(
    `SELECT trace_id                    AS traceId,
            user_question               AS userQuestion,
            intent,
            snapshot_id                 AS snapshotId,
            engine_version              AS engineVersion,
            policy_version              AS policyVersion,
            constraint_versions_json    AS constraintVersions,
            payload_schema_version      AS payloadSchemaVersion,
            consent_state               AS consentState,
            model_provider              AS modelProvider,
            model_name                  AS modelName,
            selected_candidate_id       AS selectedCandidateId,
            rejected_candidate_ids_json AS rejectedCandidateIds,
            payload,
            created_at                  AS createdAt,
            redacted_at                 AS redactedAt
       FROM decision_traces
      WHERE trace_id = ? AND uid = ?
      LIMIT 1`,
    [traceId, uid]
  );

  if (rows.length === 0) return null;

  const row = rows[0];
  return {
    ...row,
    constraintVersions: parse(row.constraintVersions),
    consentState: parse(row.consentState),
    rejectedCandidateIds: parse(row.rejectedCandidateIds) ?? [],
    payload: parse(row.payload),
    createdAt: Number(row.createdAt),
    redactedAt: row.redactedAt === null ? null : Number(row.redactedAt),
    isRedacted: row.redactedAt !== null
  };
}

/**
 * Clears the sensitive parts of a trace, leaving the record that it existed.
 *
 * Idempotent: redacting an already-redacted trace is a no-op rather than an
 * error, because the caller's intent is satisfied either way and failing would
 * only invite a retry loop.
 */
async function redactTrace(traceId, uid) {
  const [result] = await pool.execute(
    `UPDATE decision_traces
        SET user_question = NULL,
            payload       = NULL,
            consent_state = NULL,
            redacted_at   = COALESCE(redacted_at, ?)
      WHERE trace_id = ? AND uid = ?`,
    [Date.now(), traceId, uid]
  );

  return result.affectedRows > 0;
}

/**
 * Removes traces past the retention window.
 *
 * Deletes rather than tombstones. A tombstone answers "this was redacted at the
 * user's request"; retention expiry is a different fact, and keeping an
 * ever-growing list of empty rows to record it serves nobody.
 */
async function purgeExpiredTraces(now = Date.now()) {
  const cutoff = now - retentionDays() * 86_400_000;
  const [result] = await pool.execute(
    'DELETE FROM decision_traces WHERE created_at < ?',
    [cutoff]
  );
  return result.affectedRows;
}

const parse = (value) => {
  if (value === null || value === undefined) return null;
  return typeof value === 'string' ? JSON.parse(value) : value;
};

module.exports = {
  writeTrace,
  readTrace,
  redactTrace,
  purgeExpiredTraces,
  retentionDays,
  DEFAULT_RETENTION_DAYS
};
