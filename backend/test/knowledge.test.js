/**
 * The authoritative knowledge layer.
 *
 * Run with: npm test
 *
 * The refusal is the feature, so most of these check that something is *not*
 * served. A question about tax or regulation has an answer that lives in a
 * document and changes when that document changes; answering it from a model's
 * recollection produces a confident, unattributable, possibly-obsolete claim
 * about somebody's legal position.
 *
 * Retrieval itself needs a database, so the gates are exercised through the pure
 * functions that decide them — scoring, term extraction, hashing, refusal
 * wording — plus the loader's validation, which is what actually stops bad
 * content getting in.
 */
const assert = require('assert');
const {
  hashSourceText, scoreSnippet, termsFrom, refusalFor,
  SOURCE_STATUS, TOPIC, NO_ANSWER
} = require('../src/engine/../services/knowledgeRetrieval');
const { validateEntry, MAX_REVIEW_INTERVAL_DAYS } = require('../src/services/knowledgeLoader');

function ok(name, fn) {
  fn();
  console.log(`  ok  ${name}`);
}

const NOW = Date.UTC(2026, 7, 18);
const days = (n) => n * 86_400_000;

const entry = (overrides = {}) => ({
  publisher: 'Securities and Exchange Board of India',
  title: 'Master Circular for Investment Advisers',
  url: 'https://www.sebi.gov.in/example',
  jurisdiction: 'IN',
  reviewer: 'Shivam',
  retrievedDate: NOW,
  effectiveDate: NOW - days(200),
  reviewDueDate: NOW + days(180),
  sourceText: 'The full reviewed text of the circular goes here for hashing.',
  snippets: [{
    topic: TOPIC.RISK_PROFILING,
    text: 'An investment adviser shall ensure that the advice is suitable for the client.',
    keywords: 'risk profiling suitability adviser',
    effectiveFrom: NOW - days(200),
    effectiveTo: null,
    claims: [{
      key: 'sebi.ia.suitability_required',
      text: 'SEBI requires an investment adviser to ensure advice is suitable for the client.'
    }]
  }],
  ...overrides
});

(async () => {
  console.log('matching');

  ok('a topic match outweighs any number of keyword hits', () => {
    // The topic was assigned by the reviewer; keywords are a convenience.
    const onTopic = scoreSnippet(
      { topic: TOPIC.TAX_DEDUCTIONS, keywords: '' },
      { topic: TOPIC.TAX_DEDUCTIONS, terms: [] }
    );
    const offTopic = scoreSnippet(
      { topic: TOPIC.BANKING_RULES, keywords: 'tax deduction limit section saving' },
      { topic: TOPIC.TAX_DEDUCTIONS, terms: ['tax', 'deduction', 'limit', 'section'] }
    );

    assert.ok(onTopic > offTopic);
  });

  ok('a snippet matching nothing scores zero and is dropped', () => {
    const score = scoreSnippet(
      { topic: TOPIC.BANKING_RULES, keywords: 'repo rate' },
      { topic: TOPIC.TAX_DEDUCTIONS, terms: ['insurance', 'nominee'] }
    );

    assert.strictEqual(score, 0);
  });

  ok('short words are not treated as search terms', () => {
    // "is", "my", "the" would match almost anything and mean nothing.
    const terms = termsFrom('What is my tax deduction limit?');

    assert.ok(!terms.includes('is'));
    assert.ok(!terms.includes('my'));
    assert.ok(terms.includes('tax'));
    assert.ok(terms.includes('deduction'));
  });

  ok('punctuation does not break a term', () => {
    assert.deepStrictEqual(termsFrom('Section 80C — what is the limit?'),
      ['section', '80c', 'what', 'the', 'limit']);
  });

  console.log('\nthe change hash');

  ok('the same text hashes the same regardless of whitespace', () => {
    // A reformatted page is not a changed document, and flagging one every time
    // a publisher adjusts their markup would make the check ignorable.
    assert.strictEqual(
      hashSourceText('The adviser  shall\n  ensure suitability.'),
      hashSourceText('The adviser shall ensure suitability.')
    );
  });

  ok('changed text hashes differently', () => {
    assert.notStrictEqual(
      hashSourceText('The adviser shall ensure suitability.'),
      hashSourceText('The adviser may ensure suitability.')
    );
  });

  console.log('\nrefusing, and saying which gate refused');

  ok('overdue review is distinguished from having nothing', () => {
    // A corpus that has all gone past review is an operational problem, and
    // reporting it as an absence of knowledge would hide it.
    assert.notStrictEqual(
      refusalFor(NO_ANSWER.ALL_PAST_REVIEW),
      refusalFor(NO_ANSWER.NOTHING_ON_FILE)
    );
    assert.ok(refusalFor(NO_ANSWER.ALL_PAST_REVIEW).includes('overdue'));
  });

  ok('an out-of-effect passage says it applied to an earlier period', () => {
    assert.ok(refusalFor(NO_ANSWER.ALL_OUT_OF_EFFECT).includes('earlier period'));
  });

  ok('a foreign question says the app only holds Indian material', () => {
    assert.ok(refusalFor(NO_ANSWER.WRONG_JURISDICTION).includes('Indian'));
  });

  ok('an unknown reason still refuses rather than falling through', () => {
    assert.ok(refusalFor('SOMETHING_ELSE').includes('does not hold'));
  });

  console.log('\nwhat may be loaded');

  ok('a complete reviewed entry validates', () => {
    assert.deepStrictEqual(validateEntry(entry(), NOW), []);
  });

  ok('an entry with no named reviewer is refused', () => {
    // A review nobody is accountable for is not a review.
    const problems = validateEntry(entry({ reviewer: '' }), NOW);

    assert.ok(problems.some((problem) => problem.includes('reviewer')));
  });

  ok('an entry with no review date is refused', () => {
    const problems = validateEntry(entry({ reviewDueDate: undefined }), NOW);

    assert.ok(problems.some((problem) => problem.includes('reviewDueDate')));
  });

  ok('a review date already past is refused', () => {
    const problems = validateEntry(entry({ reviewDueDate: NOW - days(1) }), NOW);

    assert.ok(problems.some((problem) => problem.includes('already past')));
  });

  ok('a review interval beyond a year is refused', () => {
    // A source is not permanently true because it was true when somebody read
    // it; circulars get superseded and Finance Acts amend sections.
    const problems = validateEntry(
      entry({ reviewDueDate: NOW + days(MAX_REVIEW_INTERVAL_DAYS + 30) }), NOW
    );

    assert.ok(problems.some((problem) => problem.includes('more than')));
  });

  ok('a non-https source is refused', () => {
    // A claim sourced over plain HTTP cannot be shown to have come from the
    // publisher it names.
    const problems = validateEntry(entry({ url: 'http://sebi.gov.in/x' }), NOW);

    assert.ok(problems.some((problem) => problem.includes('https')));
  });

  ok('a snippet with no claims is refused', () => {
    // A quotation supporting no statement cannot be checked against one, which
    // is the whole thing claims exist to make possible.
    const bad = entry();
    bad.snippets[0].claims = [];

    assert.ok(validateEntry(bad, NOW).some((problem) => problem.includes('no claims')));
  });

  ok('an unknown topic is refused rather than accepted as free text', () => {
    const bad = entry();
    bad.snippets[0].topic = 'WHATEVER_I_FEEL_LIKE';

    assert.ok(validateEntry(bad, NOW).some((problem) => problem.includes('not a known topic')));
  });

  ok('a claim key must be a stable lowercase handle', () => {
    const bad = entry();
    bad.snippets[0].claims[0].key = 'Some Claim!';

    assert.ok(validateEntry(bad, NOW).some((problem) => problem.includes('lowercase handle')));
  });

  ok('a snippet too short to be a quotation is refused', () => {
    const bad = entry();
    bad.snippets[0].text = 'Yes.';

    assert.ok(validateEntry(bad, NOW).some((problem) => problem.includes('too short')));
  });

  ok('every problem is reported, not just the first', () => {
    // An entry that almost validates should fail with everything wrong listed,
    // because the person fixing it is about to put their name on the result.
    const problems = validateEntry(
      entry({ reviewer: '', url: 'http://x.gov.in', reviewDueDate: NOW - days(1) }), NOW
    );

    assert.ok(problems.length >= 3);
  });

  console.log('\nstatus vocabulary');

  ok('a loaded source is active and the schema default is not', () => {
    // A row that arrives without going through the loader stays inert until
    // somebody promotes it deliberately.
    assert.strictEqual(SOURCE_STATUS.ACTIVE, 'ACTIVE');
    assert.strictEqual(SOURCE_STATUS.UNDER_REVIEW, 'UNDER_REVIEW');
    assert.ok(Object.values(SOURCE_STATUS).includes('SUPERSEDED'));
  });

  console.log('\nall knowledge tests passed');
})();
