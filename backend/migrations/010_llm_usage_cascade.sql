-- 010 — Make usage records follow the account.
--
-- `llm_usage` has always been keyed by uid and has never had a foreign key, so
-- deleting an account left its rows behind: a record that a particular person
-- used the service, when, how often and at what cost, with nothing left to
-- attach it to and no path that would ever remove it.
--
-- The rows hold no financial content — token counts and a model name, never the
-- text of a request — but they are still a record of somebody's activity, and a
-- deletion request that leaves them is not a deletion. Found by the account-
-- deletion audit rather than by anybody noticing.
--
-- Orphans are cleared first. An account deleted before this migration has rows
-- pointing at a uid that no longer exists, and the constraint cannot be added
-- while they are there.

DELETE FROM llm_usage
 WHERE uid NOT IN (SELECT uid FROM users);

ALTER TABLE llm_usage
    ADD CONSTRAINT fk_llm_usage_user
    FOREIGN KEY (uid) REFERENCES users(uid)
    ON DELETE CASCADE;
