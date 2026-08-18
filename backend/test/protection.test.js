/**
 * Insurance, risk and portfolio.
 *
 * Run with: npm test
 *
 * These three share one failure mode, and it is the reason they are tested
 * together. Each is asked a question it often cannot answer — is the cover
 * enough, does this mix suit you, how much risk can you take — and in each case
 * the tempting default is confidently wrong in a way nobody would catch.
 * Reporting "adequate cover" from an empty policy table, or grading a portfolio
 * against an assumed moderate profile, produces a verdict indistinguishable from
 * a real one. Every test below pins a refusal.
 */
const assert = require('assert');
const money = require('../src/engine/money');
const { readAsset, LIQUIDITY, OWNERSHIP } = require('../src/engine/netWorthEngine');
const {
  assessProtection,
  PROTECTION_STATUS,
  LIFE_COVER_MULTIPLE
} = require('../src/engine/insuranceEngine');
const {
  assessPortfolio,
  alignmentFor,
  bindingLevel,
  PORTFOLIO_STATUS
} = require('../src/engine/portfolioEngine');
const {
  readRiskProfile,
  suggestedCapacity,
  requiredRiskFor,
  RISK_LEVEL,
  QUESTIONNAIRE_VERSION
} = require('../src/engine/riskProfile');

function ok(name, fn) {
  fn();
  console.log(`  ok  ${name}`);
}

const NOW = Date.UTC(2026, 7, 17);

const policyRow = (overrides = {}) => ({
  local_id: 1,
  label: 'Term plan',
  policy_type: 'TERM_LIFE',
  provider: 'Acme Life',
  sum_assured: '10000000.00',
  currency: 'INR',
  premium_amount: '12000.00',
  premium_cadence: 'YEARLY',
  policy_end_date: NOW + 3650 * 86_400_000,
  nominee_set: 1,
  ...overrides
});

const riskRow = (overrides = {}) => ({
  risk_tolerance: RISK_LEVEL.MODERATE,
  risk_capacity: RISK_LEVEL.MODERATE,
  risk_need: RISK_LEVEL.MODERATE,
  questionnaire_version: QUESTIONNAIRE_VERSION,
  answers_json: null,
  assessment_date: NOW - 30 * 86_400_000,
  limitations: null,
  user_confirmed: 1,
  ...overrides
});

const assetRow = (overrides = {}) => ({
  local_id: 1,
  label: 'Index fund',
  asset_type: 'MUTUAL_FUND',
  current_value: '500000.00',
  currency: 'INR',
  valuation_date: NOW,
  liquidity_class: LIQUIDITY.LIQUID_INVESTMENT,
  lock_in_until: null,
  ownership: OWNERSHIP.SELF,
  verification_source: 'MANUAL',
  ...overrides
});

const asset = (overrides) => readAsset(assetRow(overrides), 'INR');

const protect = (policies, income = money.money(10000000, 'INR')) => assessProtection({
  policyRows: policies,
  monthlyIncome: income,
  essentialMonthlySpend: money.money(3000000, 'INR'),
  currency: 'INR',
  now: NOW
});

(async () => {
  console.log('insurance: absence is not an all-clear');

  ok('no policies at all reports unknown, never adequate', () => {
    // The whole design turns on this. "You have no life cover" and "you have not
    // told us about your life cover" are opposite conclusions from identical data.
    const result = protect([]);

    assert.strictEqual(result.overall, PROTECTION_STATUS.UNKNOWN);
    assert.strictEqual(result.life.status, PROTECTION_STATUS.UNKNOWN);
    assert.ok(result.caveats.some((caveat) => caveat.includes('either way')));
  });

  ok('policies of other kinds make a missing kind a real gap', () => {
    // Once somebody has recorded their motor and health cover, the absence of
    // life cover is evidence rather than an empty table.
    const result = protect([policyRow({ policy_type: 'HEALTH', sum_assured: '500000.00' })]);

    assert.strictEqual(result.life.status, PROTECTION_STATUS.GAP_DETECTED);
    assert.ok(result.life.note.includes('none of this kind'));
  });

  ok('cover below the convention is a gap with a figure', () => {
    const result = protect([policyRow({ sum_assured: '2000000.00' })]);

    // 10 × ₹1,00,000 × 12 = ₹1,20,00,000 required against ₹20,00,000 held.
    assert.strictEqual(result.life.status, PROTECTION_STATUS.GAP_DETECTED);
    assert.strictEqual(result.life.required.minorUnits, 1200000000);
    assert.strictEqual(result.life.shortfall.minorUnits, 1000000000);
  });

  ok('cover at the convention reads as adequate', () => {
    const result = protect([
      policyRow({ sum_assured: '12000000.00' }),
      policyRow({ local_id: 2, policy_type: 'HEALTH', sum_assured: '400000.00' })
    ]);

    assert.strictEqual(result.life.status, PROTECTION_STATUS.ADEQUATE);
    assert.strictEqual(result.health.status, PROTECTION_STATUS.ADEQUATE);
    assert.strictEqual(result.overall, PROTECTION_STATUS.ADEQUATE);
  });

  ok('a lapsed policy is not counted as cover', () => {
    const result = protect([
      policyRow({ sum_assured: '12000000.00', policy_end_date: NOW - 86_400_000 })
    ]);

    assert.strictEqual(result.life.status, PROTECTION_STATUS.GAP_DETECTED);
    assert.strictEqual(result.lapsedPolicies.length, 1);
    assert.ok(result.caveats.some((caveat) => caveat.includes('passed their end date')));
  });

  ok('no income baseline means cover cannot be judged', () => {
    const result = protect([policyRow()], null);

    assert.strictEqual(result.life.status, PROTECTION_STATUS.UNKNOWN);
    assert.ok(result.life.note.includes('how much would be enough'));
  });

  ok('a missing nominee is surfaced, and unknown is not treated as missing', () => {
    const withNone = protect([policyRow({ nominee_set: 0 })]);
    const withUnknown = protect([policyRow({ nominee_set: null })]);

    assert.ok(withNone.caveats.some((caveat) => caveat.includes('no nominee recorded')));
    assert.ok(withUnknown.caveats.some((caveat) => caveat.includes('is not recorded')));
  });

  ok('the cover multiple is labelled a convention, not a calculation', () => {
    const result = protect([policyRow()]);

    assert.strictEqual(result.coverBasis.kind, 'CONVENTION');
    assert.strictEqual(result.coverBasis.lifeCoverMultiple, LIFE_COVER_MULTIPLE);
    assert.ok(result.caveats.some((caveat) => caveat.includes('rules of thumb')));
  });

  ok('a policy in another currency throws rather than being summed', () => {
    assert.throws(() => protect([policyRow({ currency: 'USD' })]), /USD/);
  });

  console.log('\nrisk: nothing until the user confirms');

  ok('an absent profile is unknown', () => {
    const profile = readRiskProfile(null, NOW);

    assert.strictEqual(profile.known, false);
    assert.strictEqual(profile.tolerance, null);
    assert.ok(profile.reason.includes('No risk profile'));
  });

  ok('an unconfirmed questionnaire yields nothing, even with answers stored', () => {
    // Started and abandoned is not consent. Reading those answers would apply a
    // profile the user never agreed to.
    const profile = readRiskProfile(riskRow({ user_confirmed: 0 }), NOW);

    assert.strictEqual(profile.known, false);
    assert.strictEqual(profile.tolerance, null);
    assert.strictEqual(profile.capacity, null);
    assert.ok(profile.reason.includes('never confirmed'));
  });

  ok('a confirmed profile is read in full', () => {
    const profile = readRiskProfile(riskRow(), NOW);

    assert.strictEqual(profile.known, true);
    assert.strictEqual(profile.tolerance, RISK_LEVEL.MODERATE);
    assert.strictEqual(profile.isStale, false);
  });

  ok('an old profile is reported stale rather than discarded', () => {
    const profile = readRiskProfile(
      riskRow({ assessment_date: NOW - 900 * 86_400_000 }), NOW
    );

    assert.strictEqual(profile.known, true);
    assert.strictEqual(profile.isStale, true);
  });

  ok('tolerance exceeding capacity is surfaced, not averaged away', () => {
    // The single most important thing a risk profile can reveal, and exactly
    // what one combined score would erase.
    const profile = readRiskProfile(
      riskRow({ risk_tolerance: RISK_LEVEL.HIGH, risk_capacity: RISK_LEVEL.LOW }), NOW
    );

    assert.strictEqual(profile.mismatch.kind, 'TOLERANCE_EXCEEDS_CAPACITY');
    assert.ok(profile.mismatch.note.includes('Capacity is the binding one'));
  });

  ok('suggested capacity is offered as a starting point, never stored', () => {
    const suggestion = suggestedCapacity({
      coverageMonths: 8, debtServiceRatio: 0.1, incomeStability: 'STABLE'
    });

    assert.strictEqual(suggestion.level, RISK_LEVEL.HIGH);
    assert.ok(suggestion.reason.includes('for you to confirm'));
  });

  ok('capacity cannot be suggested without a reserve figure', () => {
    const suggestion = suggestedCapacity({ coverageMonths: null });

    assert.strictEqual(suggestion.level, null);
  });

  ok('a goal reachable by saving needs no investment risk', () => {
    const required = requiredRiskFor({
      shortfall: money.money(10000000, 'INR'),
      availableMonthly: money.money(1000000, 'INR'),
      monthsRemaining: 12
    });

    assert.strictEqual(required.level, RISK_LEVEL.LOW);
  });

  ok('a goal saving cannot reach needs risk, and says a change is an option', () => {
    const required = requiredRiskFor({
      shortfall: money.money(100000000, 'INR'),
      availableMonthly: money.money(1000000, 'INR'),
      monthsRemaining: 12
    });

    assert.strictEqual(required.level, RISK_LEVEL.HIGH);
    assert.ok(required.reason.includes('or a change'));
  });

  console.log('\nportfolio: shape always, suitability only when known');

  ok('no confirmed profile means alignment is unknown, not moderate', () => {
    // Grading against an assumed profile could tell a cautious investor their
    // equity-heavy holdings are fine.
    const result = assessPortfolio({
      assets: [asset(), asset({ local_id: 2, asset_type: 'FIXED_DEPOSIT' })],
      riskProfile: readRiskProfile(null, NOW),
      currency: 'INR',
      now: NOW
    });

    assert.strictEqual(result.alignment.status, PORTFOLIO_STATUS.UNKNOWN);
    assert.ok(result.caveats.some((caveat) => caveat.includes('cannot say whether it suits')));
  });

  ok('concentration is reported even without a profile', () => {
    // Observable without knowing anything about the person: a holding that is
    // most of what somebody owns is a concentration whoever they are.
    const result = assessPortfolio({
      assets: [
        asset({ local_id: 1, current_value: '900000.00' }),
        asset({ local_id: 2, current_value: '100000.00', asset_type: 'FIXED_DEPOSIT' })
      ],
      riskProfile: readRiskProfile(null, NOW),
      currency: 'INR',
      now: NOW
    });

    assert.strictEqual(result.status, PORTFOLIO_STATUS.CONCENTRATED);
    assert.strictEqual(result.concentrations.length, 1);
  });

  ok('a mix inside the suggested band is aligned', () => {
    const result = assessPortfolio({
      assets: [
        asset({ local_id: 1, current_value: '500000.00', asset_type: 'MUTUAL_FUND' }),
        asset({ local_id: 2, current_value: '500000.00', asset_type: 'FIXED_DEPOSIT' })
      ],
      riskProfile: readRiskProfile(riskRow(), NOW),
      currency: 'INR',
      now: NOW
    });

    assert.strictEqual(result.alignment.status, PORTFOLIO_STATUS.ALIGNED);
  });

  ok('capacity binds rather than tolerance', () => {
    // Somebody willing to take more risk than they can afford is measured
    // against what they can afford.
    const profile = readRiskProfile(
      riskRow({ risk_tolerance: RISK_LEVEL.HIGH, risk_capacity: RISK_LEVEL.LOW }), NOW
    );

    assert.strictEqual(bindingLevel(profile), RISK_LEVEL.LOW);
  });

  ok('a growth-heavy mix on a low-capacity profile is flagged for review', () => {
    const profile = readRiskProfile(
      riskRow({ risk_tolerance: RISK_LEVEL.HIGH, risk_capacity: RISK_LEVEL.LOW }), NOW
    );
    const result = alignmentFor(0.85, profile);

    assert.strictEqual(result.status, PORTFOLIO_STATUS.REVIEW);
    assert.ok(result.note.includes('larger swings'));
  });

  ok('property and family holdings are left out of the investable mix', () => {
    // Counting a home would make almost every household look overwhelmingly
    // concentrated in one illiquid asset.
    const result = assessPortfolio({
      assets: [
        asset({ local_id: 1, current_value: '500000.00' }),
        asset({ local_id: 2, current_value: '9000000.00', liquidity_class: LIQUIDITY.PHYSICAL }),
        asset({ local_id: 3, current_value: '500000.00', ownership: OWNERSHIP.FAMILY })
      ],
      riskProfile: readRiskProfile(riskRow(), NOW),
      currency: 'INR',
      now: NOW
    });

    assert.strictEqual(result.total.minorUnits, 50000000);
    assert.ok(result.caveats.some((caveat) => caveat.includes('left out')));
  });

  ok('nothing investable means unknown, not a verdict', () => {
    const result = assessPortfolio({
      assets: [], riskProfile: readRiskProfile(riskRow(), NOW), currency: 'INR', now: NOW
    });

    assert.strictEqual(result.status, PORTFOLIO_STATUS.UNKNOWN);
  });

  ok('the output says plainly it is not a recommendation', () => {
    const result = assessPortfolio({
      assets: [asset()], riskProfile: readRiskProfile(riskRow(), NOW), currency: 'INR', now: NOW
    });

    assert.ok(result.caveats.some((caveat) => caveat.includes('not a recommendation')));
  });

  console.log('\nall protection, risk and portfolio tests passed');
})();
