-- 009 — The authoritative knowledge layer.
--
-- Some questions are not about the user's own figures. "What is the deduction
-- limit under 80C" has an answer that lives in a government document and changes
-- when that document changes, and a model's recollection of it is exactly the
-- kind of confident, stale, unattributable claim this product must not make.
--
-- So those answers come from stored, reviewed, cited text or they do not come at
-- all.
--
-- Three tables rather than one, and the split is the point.
--
-- A *source* is a document. A *snippet* is a passage from it. A *claim* is the
-- specific statement that passage supports. Citing a whole document for anything
-- it happens to contain is how a footnote becomes decoration — the reader cannot
-- check it, and nobody notices when the document stops saying what was claimed.
-- Claims make the support checkable at the level it was actually made.
--
-- Staleness is a review date, not an age. The first design used age and it was
-- wrong in both directions: a circular published last month can be superseded
-- next week, and a section of the Income Tax Act can be twenty years old and
-- perfectly current. What matters is whether a person has confirmed recently
-- that it still says what we claim, which is why `reviewer` and
-- `review_due_date` are not optional.

CREATE TABLE IF NOT EXISTS knowledge_sources (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,

    publisher        VARCHAR(160) NOT NULL,
    title            VARCHAR(500) NOT NULL,
    url              VARCHAR(1000) NOT NULL,
    jurisdiction     VARCHAR(8) NOT NULL DEFAULT 'IN',

    -- Who confirmed this says what we claim it says. Named, not a boolean: a
    -- review nobody is accountable for is not a review.
    reviewer         VARCHAR(160) NOT NULL,
    retrieved_date   BIGINT NOT NULL,
    -- When the document itself took effect, which is not when we fetched it.
    effective_date   BIGINT DEFAULT NULL,
    -- When somebody must look again. Past this, the source stops being served
    -- rather than being served with a caveat.
    review_due_date  BIGINT NOT NULL,

    -- Of the text as reviewed. A re-check that disagrees means the document
    -- changed under us, and the right response is to stop citing it until a
    -- human has looked — not to serve the old claim against new text.
    source_hash      CHAR(64) NOT NULL,

    status           ENUM('ACTIVE', 'SUPERSEDED', 'UNDER_REVIEW')
                     NOT NULL DEFAULT 'UNDER_REVIEW',
    -- What replaced it, when a newer circular or amendment exists.
    superseded_by    BIGINT DEFAULT NULL,

    updated_at       BIGINT NOT NULL,

    INDEX idx_knowledge_source_status (status, jurisdiction, review_due_date),

    CONSTRAINT fk_knowledge_superseded_by
        FOREIGN KEY (superseded_by) REFERENCES knowledge_sources(id)
        ON DELETE SET NULL
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Snippets: the passages actually quoted.
--
-- Stored rather than fetched at answer time. A live fetch would make every
-- answer depend on a government site being up, and would mean the text serving
-- an answer today is not necessarily the text somebody reviewed.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS knowledge_snippets (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_id      BIGINT NOT NULL,

    -- What this is about, from a controlled list. Matching on a free-text topic
    -- would make retrieval depend on how somebody happened to word it.
    topic          VARCHAR(64) NOT NULL,
    snippet_text   TEXT NOT NULL,
    -- Space-separated terms for matching. Deliberately crude: this is a curated
    -- corpus of a few dozen entries, and embedding search would be machinery
    -- without a problem to solve.
    keywords       VARCHAR(500) NOT NULL DEFAULT '',

    -- The window in which this text is the current answer. A tax rate that
    -- applied to one assessment year must not answer a question about another.
    effective_from BIGINT NOT NULL,
    effective_to   BIGINT DEFAULT NULL,

    updated_at     BIGINT NOT NULL,

    INDEX idx_snippet_topic (topic, effective_from),
    INDEX idx_snippet_source (source_id),

    CONSTRAINT fk_snippet_source
        FOREIGN KEY (source_id) REFERENCES knowledge_sources(id)
        ON DELETE CASCADE
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Claims: the specific statements a snippet supports.
--
-- The table that stops a citation being decoration. "SEBI says investment
-- advisers must assess suitability" is a claim; the snippet is the passage that
-- says so; the source is the document it came from. Anything the app asserts as
-- regulatory fact resolves to a row here or is not asserted.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS knowledge_claims (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    snippet_id   BIGINT NOT NULL,

    -- The statement itself, in the app's own words, as shown to a user.
    claim_text   VARCHAR(1000) NOT NULL,
    -- A stable handle so code can cite a claim without embedding its wording.
    claim_key    VARCHAR(120) NOT NULL,

    updated_at   BIGINT NOT NULL,

    UNIQUE KEY unique_claim_key (claim_key),
    INDEX idx_claim_snippet (snippet_id),

    CONSTRAINT fk_claim_snippet
        FOREIGN KEY (snippet_id) REFERENCES knowledge_snippets(id)
        ON DELETE CASCADE
);
