# Financial engine — beta readiness

What is verified, what is not, and what was deliberately left out. Written so the
gap between *tested* and *proven* is visible rather than assumed.

Last updated at the end of Stage 9.

---

## Verified automatically

These run on every `npm test` / `./gradlew build` and fail the build if broken.

**Correctness of the engine.** Every calculator has its own suite: money
arithmetic in minor units, complete-period baselines, cash flow, debt and
amortisation, net worth, emergency reserve, goals, protection, portfolio,
scenarios, constraints, recommendations. Load-bearing logic has been
mutation-checked — the guard is deliberately broken, the failure confirmed, then
restored.

**The separations the whole design rests on.** Actual figures never move when an
assumption changes. Observed state is byte-identical across all three scenarios.
A commitment already paid is never counted twice. A rejected candidate can never
become the selected one. A transfer between the user's own accounts leaves net
worth unchanged.

**Refusals.** An unknown loan rate produces no interest figure. An unconfirmed
risk profile produces no portfolio verdict. An empty policy table produces
`UNKNOWN`, never "adequate". A near-term goal is never projected at an equity
return. No approved assumption means no projection at all.

**The numeric gate.** A model cannot author a financial figure: unregistered
`{{references}}` reject the reply, and currency-shaped literals in a template are
refused even when no reference was declared.

**Adversarial handling.** Prompt injection, requests for specific securities,
attempts to reach another user's data or the instructions themselves, and history
turns claiming a `system` role.

**Cross-cutting audits** (`test/hardening.test.js`) — every route authenticated,
rate limited, and ownership-checked wherever it trusts a client-supplied uid; no
prompt or UI string promising a return; disclaimers present; decision traces
written and redacted but never edited; every user-owned table cascading from
`users`; chat and extraction caps genuinely independent; snapshot hashing stable
across 300 user/period combinations; migrations numbered without gaps and
splitting into valid statements.

---

## Not verified — needs a live database

**Nothing in this repository has run against a real MySQL instance.** Ten
migrations, sixteen route files, composite foreign keys, `DECIMAL` round-trips
and `JSON` column handling are all unexercised. The engine logic beneath them is
thoroughly tested; the SQL is not.

Before beta:

- [ ] `npm run migrate` against an empty database, then again — the second run
      must be a no-op.
- [ ] `npm run migrate` against a database created from `schema.sql`, confirming
      the two paths converge.
- [ ] Start the server and confirm `assertSchemaIsCurrent()` passes.
- [ ] Round-trip every sync route: write, read back, confirm decimals are exact.
- [ ] `GET /financial-state/snapshot` end to end, confirming the stored snapshot
      reads back identically.
- [ ] Delete a test account and confirm every table is empty for that uid.

## Not verified — needs a running app

- [ ] Room migration 13→15 on a device with **existing rows**, not a fresh
      install. Migrations are byte-verified against Room's exported schema, which
      catches structural drift but not data loss.
- [ ] The Stage 7 end-to-end check: enable chat, ask a goal question, confirm
      every figure matches `/recommendations` exactly, confirm a securities
      question is refused, confirm a pending-sync state is surfaced.
- [ ] Accessibility pass (TalkBack, font scaling, contrast).
- [ ] Whether the offline Cash Flow Snapshot labelling actually reads clearly to
      somebody seeing it for the first time.

## Not verified — needs infrastructure

- [ ] Load testing on `/recommendations` and `/chat` under realistic concurrency.
- [ ] Race conditions: two devices syncing while a snapshot is built; a snapshot
      requested mid-sweep.
- [ ] Backup and restore including the ten new tables.

---

## Deliberately not built

**The knowledge corpus is empty.** Generating regulatory or tax text from a
model's recollection would produce authoritative-looking claims about somebody's
legal position that no document supports — the exact failure the layer exists to
prevent. Content needs real sourcing and a named reviewer; see
`KNOWLEDGE_SEEDING.md`. Until then, tax and regulation questions are declined
honestly, which is tested.

**Currency conversion.** There is no exchange-rate source, so the engine reports
one currency and states what it excluded. Mixing currencies hard-fails rather
than silently converting. This is a stated non-goal, not an oversight.

**Deferred by the specification itself**, and still deferred: Account Aggregator
integration (requires RBI-licensed onboarding), a validated composite health
score (contradicted elsewhere in the same spec, which asks for component
statuses), and regulated investment functionality (requires SEBI registration and
a legal workstream). None of these are engineering gaps.

---

## Standing obligations

**Assumption sets** carry a version, a source and an effective window. Revising
one creates a new version; old runs keep pointing at what they actually used.
They are planning assumptions and are labelled as such wherever they surface.

**Knowledge sources** carry a reviewer and a review date. Retrieval stops serving
a source the moment its date passes — this needs somebody to actually re-check
them, and `loadKnowledge.js --inventory` lists what is overdue.

**Decision traces** are retained for `DECISION_TRACE_RETENTION_DAYS` (default
180). Redaction tombstones rather than deletes, so an audit trail stays reliable.

**This is not a licensed advice product.** The constraint engine refuses
personalised investment suggestions without a confirmed risk profile, and the
chat boundary redirects product questions to a SEBI-registered adviser. Moving
into regulated advice needs legal review before, not after.
