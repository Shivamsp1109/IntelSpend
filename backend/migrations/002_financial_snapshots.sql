-- 002 — Financial state snapshots.
--
-- What the engine saw, kept whole rather than recomputed.
--
-- A decision trace that points at a snapshot has to be able to show the figures
-- the recommendation actually rested on. Storing only a hash cannot do that: the
-- expenses, goals and commitments behind it keep changing, so re-deriving the
-- state a month later answers a different question than the one that was asked,
-- and the trace quietly stops explaining the thing it claims to explain.
--
-- So the observed state and its data-quality assessment are written out in full
-- and never updated. The hash is for detecting whether anything moved between
-- two snapshots, not for reconstructing one.

CREATE TABLE IF NOT EXISTS financial_snapshots (
    snapshot_id           CHAR(36) PRIMARY KEY,
    uid                   VARCHAR(128) NOT NULL,

    -- The immutable payloads. JSON rather than a wide column set because the
    -- shape is versioned by payload_schema_version and will grow as domains
    -- arrive; a schema change per stage would rewrite history each time.
    observed_state_json   JSON NOT NULL,
    data_quality_json     JSON NOT NULL,

    -- How complete the server believed its own copy to be: what the device
    -- reported as still unsynced, and when it last finished a clean sweep.
    -- Without this a snapshot cannot say whether it was working from everything
    -- the user had actually recorded.
    source_watermarks_json JSON NOT NULL,

    snapshot_hash         CHAR(64) NOT NULL,
    engine_version        VARCHAR(20) NOT NULL,
    payload_schema_version INT NOT NULL,

    computed_at           BIGINT NOT NULL,
    period_start          BIGINT NOT NULL,
    period_end            BIGINT NOT NULL,
    currency              VARCHAR(10) NOT NULL DEFAULT 'INR',

    -- Carried explicitly. Period boundaries are calendar facts in the user's own
    -- timezone; recomputing them later against the server's locale moves a
    -- month's edge and silently changes which transactions were in it.
    timezone              VARCHAR(64) NOT NULL DEFAULT 'Asia/Kolkata',

    -- Newest-first lookup per user, which is every read this table gets.
    INDEX idx_snapshot_user_time (uid, computed_at DESC),

    CONSTRAINT fk_snapshot_user
        FOREIGN KEY (uid) REFERENCES users(uid)
        ON DELETE CASCADE
);
