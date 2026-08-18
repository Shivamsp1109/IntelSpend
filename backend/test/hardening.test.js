/**
 * Cross-cutting audits that can only exist once every domain does.
 *
 * Run with: npm test
 *
 * These check properties of the codebase rather than the behaviour of one
 * function, and they are written as tests rather than as a checklist because a
 * checklist is something somebody has to remember to run. Every one of them
 * guards a mistake that is easy to make while adding a feature and invisible
 * afterwards: a new route without an ownership check, a prompt that promises a
 * return, a table that survives account deletion, a trace edited in place.
 *
 * They read source files. That makes them a little unusual and is the point —
 * the thing being asserted is "no route anywhere does X", and no runtime test
 * can say that about code paths it did not happen to exercise.
 */
const assert = require('assert');
const fs = require('fs');
const path = require('path');

const money = require('../src/engine/money');
const { hashOf, buildObservedState } = require('../src/engine/financialState');
const { FEATURE } = require('../src/services/modelUsage');
const { statementsIn } = require('../src/migrate');

function ok(name, fn) {
  fn();
  console.log(`  ok  ${name}`);
}

const SRC = path.join(__dirname, '..', 'src');
const APP = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'java', 'com', 'spendwise');

const readDir = (dir, filter) =>
  fs.readdirSync(dir)
    .filter((name) => filter.test(name))
    .map((name) => ({ name, text: fs.readFileSync(path.join(dir, name), 'utf8') }));

/** Every `router.method(...)` line, which is where the middleware chain lives. */
function handlerLines(text) {
  return text.split('\n')
    .map((line, index) => ({ line, number: index + 1 }))
    .filter((entry) => /router\.(get|post|put|patch|delete)\s*\(/.test(entry.line));
}

(async () => {
  console.log('authorization boundary');

  const routes = readDir(path.join(SRC, 'routes'), /\.js$/);

  ok('every route handler requires authentication', () => {
    const unguarded = [];

    for (const route of routes) {
      for (const entry of handlerLines(route.text)) {
        if (!entry.line.includes('requireFirebaseAuth')) {
          unguarded.push(`${route.name}:${entry.number}`);
        }
      }
    }

    assert.deepStrictEqual(unguarded, [],
      `handlers without requireFirebaseAuth: ${unguarded.join(', ')}`);
  });

  /**
   * Routes whose mutating handlers legitimately have no ownership check.
   *
   * Named explicitly rather than inferred. A first version of this audit looked
   * for the literal `req.body.uid` and missed every route written as
   * `const asset = req.body; ... asset.uid` — which is most of them. It passed
   * while `requireSameUser` was removed from the asset sync route, which is
   * exactly the mistake it exists to catch.
   *
   * An allowlist inverts that: a new route is guilty until somebody adds it
   * here and says why, rather than innocent because a pattern happened not to
   * match its style.
   */
  const DERIVES_UID_FROM_TOKEN_ONLY = new Map([
    ['chat.js', 'uid comes from req.user; the body carries only a question'],
    ['extract.js', 'uid comes from req.user; the body carries a document'],
    ['recommendations.js', 'uid comes from req.user; the body carries only query options']
  ]);

  ok('every mutating handler either checks ownership or is a named exception', () => {
    // A route that trusts a uid from the request without this would serve
    // somebody else's finances to anybody who guessed an id.
    const offenders = [];

    for (const route of routes) {
      if (DERIVES_UID_FROM_TOKEN_ONLY.has(route.name)) {
        // Held to its claim: an exempt route must not read a client uid at all.
        assert.ok(!/\.uid\b/.test(route.text.replace(/req\.user\.uid/g, '')),
          `${route.name} is exempt from requireSameUser but reads a uid from the request`);
        continue;
      }

      for (const entry of handlerLines(route.text)) {
        const mutating = /router\.(post|put|patch)\s*\(/.test(entry.line);
        const readsByUid = /router\.get\s*\(\s*['"][^'"]*:uid/.test(entry.line);
        if ((mutating || readsByUid) && !entry.line.includes('requireSameUser')) {
          offenders.push(`${route.name}:${entry.number}`);
        }
      }
    }

    assert.deepStrictEqual(offenders, [],
      `mutating handlers without requireSameUser: ${offenders.join(', ')}`);
  });

  ok('every route is rate limited', () => {
    // Not security theatre: these routes read and write a user's whole
    // financial history, and two of them cost money per call.
    const unlimited = [];

    for (const route of routes) {
      for (const entry of handlerLines(route.text)) {
        if (!/syncLimiter|modelLimiter|publicLimiter/.test(entry.line)) {
          unlimited.push(`${route.name}:${entry.number}`);
        }
      }
    }

    assert.deepStrictEqual(unlimited, [],
      `handlers with no rate limiter: ${unlimited.join(', ')}`);
  });

  console.log('\nregulatory language');

  /**
   * String literals and template contents only — not comments.
   *
   * A comment saying "guaranteed to sum to the whole" is about arithmetic and
   * is fine. The audit is about what reaches a user or a prompt, and matching
   * comments would produce noise that trains everybody to ignore the result.
   */
  function userFacingText(source) {
    const withoutBlockComments = source.replace(/\/\*[\s\S]*?\*\//g, ' ');
    const withoutLineComments = withoutBlockComments.replace(/^\s*\/\/.*$/gm, ' ');
    return joinConcatenated(withoutLineComments);
  }

  /**
   * Rejoins string literals split across lines by `+`.
   *
   * Long user-facing copy is wrapped for line length, so the source reads
   * `'... not personal financial ' +\n    'advice ...'`. Matching the raw text
   * would miss any phrase that happened to straddle a wrap — which it did, on
   * the first run, for the recommendation disclaimer that was present all along.
   */
  function joinConcatenated(source) {
    return source
      .replace(/'\s*\+\s*\n?\s*'/g, '')
      .replace(/"\s*\+\s*\n?\s*"/g, '');
  }

  /**
   * Calibrated deliberately narrow.
   *
   * A first version matched a bare "no risk" and flagged "No risk profile has
   * been completed" — a sentence about a questionnaire, not about an
   * investment. An audit that cries wolf is one everybody learns to skip, so
   * each pattern here requires the phrase to be making a claim about money
   * rather than merely containing the word.
   */
  const BANNED = [
    /\bguaranteed\s+(returns?|profits?|growth|income|gains?)\b/i,
    /\bassured\s+returns?\b/i,
    /\brisk[-\s]free\s+(returns?|investment|growth)\b/i,
    /\bwill\s+(definitely|certainly|surely)\s+(grow|return|earn|increase)\b/i,
    /\b(no|zero)\s+risk\s+(of\s+loss|investment|to\s+your\s+(money|capital))\b/i,
    /\bcannot\s+lose\s+(money|value)\b/i,
    /\bsafe\s+bet\b/i
  ];

  ok('no prompt or engine string promises a return', () => {
    const offenders = [];
    const files = [
      ...readDir(path.join(SRC, 'engine'), /\.js$/),
      ...readDir(path.join(SRC, 'services'), /\.js$/)
    ];

    for (const file of files) {
      const text = userFacingText(file.text);
      for (const pattern of BANNED) {
        const match = text.match(pattern);
        if (match) offenders.push(`${file.name}: "${match[0]}"`);
      }
    }

    assert.deepStrictEqual(offenders, [], `banned language: ${offenders.join('; ')}`);
  });

  ok('no app-facing string promises a return', () => {
    const offenders = [];

    const walk = (dir) => {
      for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) { walk(full); continue; }
        if (!entry.name.endsWith('.kt')) continue;

        const text = userFacingText(fs.readFileSync(full, 'utf8'));
        for (const pattern of BANNED) {
          const match = text.match(pattern);
          if (match) offenders.push(`${entry.name}: "${match[0]}"`);
        }
      }
    };
    walk(APP);

    assert.deepStrictEqual(offenders, [], `banned language: ${offenders.join('; ')}`);
  });

  ok('the recommendation output says it is not advice', () => {
    const source = joinConcatenated(
      fs.readFileSync(path.join(SRC, 'engine', 'recommendationEngine.js'), 'utf8')
    );

    assert.ok(/not\s+personal\s+financial\s+advice/i.test(source));
  });

  ok('the chat prompt refuses specific products and names where to go instead', () => {
    const source = joinConcatenated(
      fs.readFileSync(path.join(SRC, 'services', 'chatOrchestrator.js'), 'utf8')
    );

    assert.ok(/never\s+recommend\s+a\s+specific/i.test(source));
    assert.ok(/SEBI-registered/i.test(source));
  });

  ok('the out-of-scope reply redirects rather than just declining', () => {
    // Refusing without saying where to go is a dead end. Naming a registered
    // adviser is the part that makes the boundary useful rather than obstructive.
    const source = joinConcatenated(
      fs.readFileSync(path.join(SRC, 'routes', 'chat.js'), 'utf8')
    );

    assert.ok(/SEBI-registered\s+investment\s+adviser/i.test(source));
  });

  ok('the consent screen states what is never sent', () => {
    const source = joinConcatenated(
      fs.readFileSync(path.join(APP, 'presentation', 'screens', 'ChatScreen.kt'), 'utf8')
    );

    assert.ok(/never\s+does/i.test(source), 'expected a "what never gets sent" section');
    assert.ok(/merchant\s+names/i.test(source));
    assert.ok(/not\s+financial\s+advice/i.test(source));
  });

  console.log('\naudit-log integrity');

  ok('decision traces are only ever written or redacted, never edited', () => {
    // A trace amended after the fact explains nothing. The redaction path
    // clears fields and stamps `redacted_at`; anything else touching the table
    // would make the record unreliable exactly where it matters most.
    const store = fs.readFileSync(
      path.join(SRC, 'services', 'decisionTraceStore.js'), 'utf8'
    );

    const updates = store.match(/UPDATE\s+decision_traces/gi) || [];
    assert.strictEqual(updates.length, 1, 'expected exactly one UPDATE, in redactTrace');

    const redaction = store.slice(store.indexOf('async function redactTrace'));
    assert.ok(redaction.includes('UPDATE decision_traces'), 'the only UPDATE must be the redaction');
    assert.ok(/redacted_at\s*=\s*COALESCE/i.test(redaction),
      'redaction must not overwrite an earlier redaction timestamp');
  });

  ok('no other module writes to the trace table at all', () => {
    const offenders = [];

    for (const dir of ['routes', 'services', 'engine']) {
      for (const file of readDir(path.join(SRC, dir), /\.js$/)) {
        if (file.name === 'decisionTraceStore.js') continue;
        if (/(?:INSERT\s+INTO|UPDATE|DELETE\s+FROM)\s+decision_traces/i.test(file.text)) {
          offenders.push(`${dir}/${file.name}`);
        }
      }
    }

    assert.deepStrictEqual(offenders, [],
      `traces must only be written through the store: ${offenders.join(', ')}`);
  });

  console.log('\ndeletion coverage');

  ok('every user-owned table cascades from users', () => {
    // Account deletion has to leave nothing behind. A table added without the
    // cascade would orphan somebody's financial records permanently, and it is
    // exactly the sort of thing nobody notices until asked to prove otherwise.
    const schema = fs.readFileSync(path.join(SRC, '..', 'schema.sql'), 'utf8');
    const migrations = fs.readdirSync(path.join(SRC, '..', 'migrations'))
      .filter((name) => name.endsWith('.sql'))
      .map((name) => fs.readFileSync(path.join(SRC, '..', 'migrations', name), 'utf8'))
      .join('\n');

    const all = `${schema}\n${migrations}`;
    const tables = [...all.matchAll(/CREATE TABLE IF NOT EXISTS\s+(\w+)/gi)]
      .map((match) => match[1]);

    // Tables that genuinely hold no user data, named rather than inferred.
    const notUserOwned = new Set([
      'users',              // the anchor itself
      'schema_migrations',  // migration ledger
      'assumption_sets',    // system defaults; user rows cascade via their own FK
      'knowledge_sources', 'knowledge_snippets', 'knowledge_claims' // shared corpus
    ]);

    const definitionOf = (table) => {
      const start = all.indexOf(`CREATE TABLE IF NOT EXISTS ${table}`);
      return start === -1 ? '' : all.slice(start, all.indexOf(');', start));
    };

    /**
     * Cascading through another table counts.
     *
     * `recurring_expense_cross_ref` has no direct link to users — it points at
     * `recurring`, which does. Deleting the account still removes it, and
     * demanding a direct foreign key would push a redundant one onto every join
     * table for the sake of the audit rather than the user.
     */
    const cascadesToUsers = (table, seen = new Set()) => {
      if (seen.has(table)) return false;
      seen.add(table);

      const definition = definitionOf(table);
      if (/REFERENCES\s+users\s*\(\s*uid\s*\)[\s\S]*?ON DELETE CASCADE/i.test(definition)) {
        return true;
      }

      const parents = [...definition.matchAll(
        /REFERENCES\s+(\w+)\s*\([^)]*\)\s*ON DELETE CASCADE/gi
      )].map((match) => match[1]);

      return parents.some((parent) => cascadesToUsers(parent, seen));
    };

    const missing = [...new Set(tables)]
      .filter((table) => !notUserOwned.has(table))
      .filter((table) => !cascadesToUsers(table));

    assert.deepStrictEqual(missing, [],
      `tables not cascading from users: ${missing.join(', ')}`);
  });

  console.log('\nmodel cost caps');

  ok('chat and extraction are counted separately', () => {
    assert.notStrictEqual(FEATURE.CHAT, FEATURE.EXTRACT);
  });

  ok('every usage count and insert is scoped to a feature', () => {
    // One combined budget fails in two directions: a year of imports blocking a
    // question, or a conversation blocking the next import.
    const usage = fs.readFileSync(path.join(SRC, 'services', 'modelUsage.js'), 'utf8');

    assert.ok(/WHERE uid = \? AND feature = \?/.test(usage),
      'counting must filter by feature');
    assert.ok(/INSERT INTO llm_usage[\s\S]*?feature/.test(usage),
      'recording must store the feature');
  });

  ok('the extraction usage endpoint reports only extraction', () => {
    // It is displayed against extraction's cap, so counting chat there would
    // show a quota shrinking for a reason the screen never mentions.
    const extract = fs.readFileSync(path.join(SRC, 'routes', 'extract.js'), 'utf8');
    const usageQuery = extract.slice(extract.indexOf("router.get('/usage'"));

    assert.ok(/feature = \?/.test(usageQuery.slice(0, 900)));
  });

  ok('chat enforces its own cap rather than extraction\'s', () => {
    const chat = fs.readFileSync(path.join(SRC, 'routes', 'chat.js'), 'utf8');

    assert.ok(/CHAT_MONTHLY_CAP/.test(chat));
    assert.ok(!/EXTRACT_MONTHLY_CAP/.test(chat));
    assert.ok(/countCallsThisMonth\(uid, FEATURE\.CHAT\)/.test(chat));
  });

  console.log('\nsnapshot reproducibility');

  ok('the same state hashes identically across many users and periods', () => {
    // Reproducibility is what makes a stored snapshot evidence rather than a
    // souvenir. Checked at volume because a collision or an ordering bug can
    // hide entirely in a single case.
    const now = Date.UTC(2026, 7, 18);
    const seen = new Map();

    for (let user = 0; user < 50; user += 1) {
      for (let month = 1; month <= 6; month += 1) {
        const rows = {
          expenses: [{
            id: user * 10 + month,
            local_id: month,
            amount: 1000 + user,
            currency: 'INR',
            nature: 'Spending',
            category: 'Other',
            expense_date: Date.UTC(2026, month, 5)
          }],
          incomes: [], recurring: [], goals: []
        };
        const period = { start: Date.UTC(2026, month, 1), end: Date.UTC(2026, month + 1, 0) };

        const first = hashOf(buildObservedState({
          rows, currency: 'INR', period, timezone: 'Asia/Kolkata', now
        }));
        const second = hashOf(buildObservedState({
          rows, currency: 'INR', period, timezone: 'Asia/Kolkata', now
        }));

        assert.strictEqual(first, second, `unstable hash for user ${user} month ${month}`);

        const key = `${user}:${month}`;
        assert.ok(!seen.has(first) || seen.get(first) === key,
          `hash collision between ${seen.get(first)} and ${key}`);
        seen.set(first, key);
      }
    }
  });

  console.log('\nmigrations');

  ok('every migration file splits into runnable statements', () => {
    const dir = path.join(SRC, '..', 'migrations');
    const failures = [];

    for (const name of fs.readdirSync(dir).filter((file) => file.endsWith('.sql'))) {
      const statements = statementsIn(fs.readFileSync(path.join(dir, name), 'utf8'));
      if (statements.length === 0) failures.push(`${name}: no statements`);

      // A stray semicolon inside a string literal would split a statement in
      // two and both halves would be invalid SQL.
      for (const statement of statements) {
        const quotes = (statement.match(/'/g) || []).length;
        if (quotes % 2 !== 0) failures.push(`${name}: unbalanced quotes in a statement`);
      }
    }

    assert.deepStrictEqual(failures, [], failures.join('; '));
  });

  ok('migrations are numbered without gaps or duplicates', () => {
    const numbers = fs.readdirSync(path.join(SRC, '..', 'migrations'))
      .filter((name) => name.endsWith('.sql'))
      .map((name) => Number(name.slice(0, 3)))
      .sort((a, b) => a - b);

    numbers.forEach((number, index) => {
      assert.strictEqual(number, index + 1,
        `migration numbering breaks at ${number}; expected ${index + 1}`);
    });
  });

  console.log('\nall hardening audits passed');
})();
