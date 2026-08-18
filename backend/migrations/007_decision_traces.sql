-- 007 — Decision traces.
--
-- Why a recommendation was made, kept so it can be answered later.
--
-- The trace references a snapshot rather than re-embedding the observed state.
-- The snapshot already stores that state whole and never updates it, so copying
-- it here would double the storage of the most sensitive data in the product and
-- create a second copy to keep in step — and the reason for storing the snapshot
-- at all was that re-deriving state later answers a different question.
--
-- Redaction is a tombstone rather than a delete. If a user asks for a trace to be
-- forgotten, the row stays with `redacted_at` set and its sensitive fields
-- cleared: the fact that a recommendation was made on a date is not itself
-- sensitive, and silently vanishing rows would make the audit trail unreliable
-- for everyone — including for demonstrating that a redaction happened. What is
-- cleared is what identifies or describes them: their question and the payload.
--
-- `consent_state`, `model_provider` and `model_name` are recorded from the
-- outset even though no model is in the loop yet. When one arrives in Stage 7,
-- every trace it touches needs to say what the user had agreed to at the time,
-- and adding those columns afterwards would leave the first traces unable to
-- answer it.

CREATE TABLE IF NOT EXISTS decision_traces (
    trace_id                CHAR(36) PRIMARY KEY,
    uid                     VARCHAR(128) NOT NULL,

    -- What was asked, in the user's own words. Null for a run the engine
    -- initiated. Cleared on redaction.
    user_question           TEXT DEFAULT NULL,
    intent                  VARCHAR(64) NOT NULL,

    -- The state this rested on. SET NULL rather than CASCADE on delete: pruning
    -- an old snapshot should not erase the record that a decision was made,
    -- only the detail of what it saw.
    snapshot_id             CHAR(36) DEFAULT NULL,

    -- Versions of everything that shaped the outcome. Without these, a trace
    -- read next year cannot say whether today's engine would decide the same
    -- way — which is most of what a trace is for.
    engine_version          VARCHAR(20) NOT NULL,
    policy_version          VARCHAR(20) NOT NULL,
    constraint_versions_json JSON NOT NULL,
    payload_schema_version  INT NOT NULL,

    -- What the user had agreed to when this ran. Recorded now so the first
    -- model-backed traces are not the ones that cannot answer it.
    consent_state           JSON DEFAULT NULL,
    model_provider          VARCHAR(64) DEFAULT NULL,
    model_name              VARCHAR(128) DEFAULT NULL,

    selected_candidate_id   VARCHAR(64) DEFAULT NULL,
    -- Kept deliberately. A recommendation is only explicable alongside what was
    -- considered and refused; a trace showing only the winner explains nothing.
    rejected_candidate_ids_json JSON NOT NULL,

    -- Calculations, constraint verdicts, candidates and knowledge sources.
    -- Cleared on redaction.
    payload                 JSON DEFAULT NULL,

    created_at              BIGINT NOT NULL,
    redacted_at             BIGINT DEFAULT NULL,

    INDEX idx_trace_user_time (uid, created_at DESC),
    INDEX idx_trace_snapshot (snapshot_id),
    -- Drives the retention purge without scanning the table.
    INDEX idx_trace_retention (created_at),

    CONSTRAINT fk_trace_user
        FOREIGN KEY (uid) REFERENCES users(uid)
        ON DELETE CASCADE,
    CONSTRAINT fk_trace_snapshot
        FOREIGN KEY (snapshot_id) REFERENCES financial_snapshots(snapshot_id)
        ON DELETE SET NULL
);
