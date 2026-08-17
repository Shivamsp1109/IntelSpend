-- 001 — Baseline.
--
-- Records the schema as it stood before migrations existed, so an established
-- database and a fresh one both start from the same known point.
--
-- Deliberately does nothing. Everything up to here was created either by
-- schema.sql on a fresh install or by hand-pasting the statements that the
-- startup check in src/config/db.js printed. Re-issuing that DDL is
-- unnecessary — `CREATE TABLE IF NOT EXISTS` would no-op on an established
-- database and schema.sql already covers a fresh one — and re-issuing it here
-- would mean two places to keep in step forever.
--
-- What this file buys is the ledger row. After it runs, `schema_migrations`
-- says the database is at version 1, and every migration from 002 onward can
-- assume the tables in schema.sql exist.
--
-- Fresh install order: run schema.sql once, then `npm run migrate`.

SELECT 'baseline' AS migration;
