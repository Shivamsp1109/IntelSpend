-- 008 — Chat messages.
--
-- The conversation, kept server-side so it survives a reinstall and so a trace
-- can point at the exchange that produced a recommendation.
--
-- What is stored is deliberately asymmetric. The user's own words are kept
-- because a conversation without them is unreadable. The assistant's reply is
-- stored *filled* — the figures already substituted — rather than as the
-- template plus references, because the template is an implementation detail and
-- what the user actually saw is what a later reader needs.
--
-- `rejected_reason` records the case where the model produced something the
-- numeric gate refused. Those are worth keeping: a pattern of rejections is the
-- clearest possible signal that the registry is missing a value people keep
-- asking about, and discarding them would hide the one metric that would say so.

-- Which feature spent the tokens.
--
-- Chat needs a cap that cannot be exhausted by statement extraction and vice
-- versa: a user who imports a year of statements should still be able to ask a
-- question, and somebody having a long conversation should not find their next
-- import refused. Counting all model calls together makes one budget out of two
-- that fail in different ways.
--
-- Existing rows default to EXTRACT, which is what every call before this was.
ALTER TABLE llm_usage
    ADD COLUMN feature VARCHAR(32) NOT NULL DEFAULT 'EXTRACT';

CREATE INDEX idx_llm_usage_feature ON llm_usage (uid, feature, created_at);

CREATE TABLE IF NOT EXISTS chat_messages (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    uid             VARCHAR(128) NOT NULL,
    conversation_id CHAR(36) NOT NULL,

    role            ENUM('USER', 'ASSISTANT') NOT NULL,
    content         TEXT NOT NULL,

    -- What the classifier made of the question. Null on assistant turns.
    intent          VARCHAR(32) DEFAULT NULL,

    -- The trace for this exchange, so "why did it say that" resolves all the way
    -- back to the snapshot and the constraint verdicts.
    decision_trace_id CHAR(36) DEFAULT NULL,

    -- Set when the numeric gate refused a reply. The content then holds the
    -- fallback the engine wrote instead.
    rejected_reason VARCHAR(64) DEFAULT NULL,

    created_at      BIGINT NOT NULL,

    INDEX idx_chat_conversation (uid, conversation_id, created_at),
    INDEX idx_chat_retention (created_at),

    CONSTRAINT fk_chat_user
        FOREIGN KEY (uid) REFERENCES users(uid)
        ON DELETE CASCADE,
    -- SET NULL rather than CASCADE: a purged trace should not take the visible
    -- conversation with it.
    CONSTRAINT fk_chat_trace
        FOREIGN KEY (decision_trace_id) REFERENCES decision_traces(trace_id)
        ON DELETE SET NULL
);
