/**
 * Net worth and the emergency reserve.
 *
 * Run with: npm test
 *
 * The reserve is the dangerous one. Counting a provident fund or a five-year
 * tax-saving deposit produces a comforting figure that fails exactly when it is
 * tested — the money is real and cannot be reached in the week something goes
 * wrong. Every exclusion below is a holding somebody would reasonably think of
 * as savings, and each is named with its reason rather than silently dropped.
 */
const assert = require('assert');
const money = require('../src/engine/money');
const periods = require('../src/engine/periods');
const { assessNetWorth, readAsset, LIQUIDITY, OWNERSHIP } = require('../src/engine/netWorthEngine');
const {
  assessEmergencyFund,
  eligibility,
  targetMonths,
  countsAsEssential,
  RESERVE_STATUS,
  POLICY_VERSION
} = require('../src/engine/emergencyFundEngine');
const { STABILITY } = require('../src/engine/cashFlowEngine');
const { assessDebt } = require('../src/engine/debtEngine');

function ok(name, fn) {
  fn();
  console.log(`  ok  ${name}`);
}

const ZONE = 'Asia/Kolkata';
const NOW = Date.UTC(2026, 7, 17, 6, 0);
const MONTHS = periods.completeMonths({ now: NOW, timeZone: ZONE, count: 6 });
const dayIn = (year, month, day) => periods.startOfDayInstant(year, month, day, ZONE) + 3_600_000;

const assetRow = (overrides = {}) => ({
  local_id: 1,
  label: 'Savings account',
  asset_type: 'BANK_ACCOUNT',
  current_value: '200000.00',
  currency: 'INR',
  valuation_date: NOW - 5 * 86_400_000,
  liquidity_class: LIQUIDITY.LIQUID_CASH,
  lock_in_until: null,
  ownership: OWNERSHIP.SELF,
  verification_source: 'MANUAL',
  ...overrides
});

const asset = (overrides) => readAsset(assetRow(overrides), 'INR');

const expense = (id, amount, year, month, category = 'Groceries', nature = 'Spending') => ({
  id, local_id: id, amount, currency: 'INR', nature, category,
  expense_date: dayIn(year, month, 10)
});

/** Essential spending of ₹30,000 in each of the six complete months. */
function essentialSixMonths() {
  const rows = [];
  let id = 1;
  for (let month = 2; month <= 7; month += 1) {
    rows.push(expense(id, 20000, 2026, month, 'Rent & Housing'));
    rows.push(expense(id + 50, 10000, 2026, month, 'Groceries'));
    id += 1;
  }
  return rows;
}

const reserveFor = (assets, expenseRows = essentialSixMonths(), overrides = {}) =>
  assessEmergencyFund({
    assets,
    expenseRows,
    months: MONTHS,
    incomeStability: STABILITY.STABLE,
    debtServiceRatio: null,
    currency: 'INR',
    now: NOW,
    ...overrides
  });

(async () => {
  console.log('net worth');

  ok('net worth is assets less liabilities', () => {
    const debt = assessDebt({
      loanRows: [{
        recurring_local_id: 1, principal_outstanding: '500000.00',
        outstanding_as_of: NOW, currency: 'INR', interest_rate: '9.0000',
        rate_type: 'FIXED', interest_compounding: 'MONTHLY',
        scheduled_payment: '10000.00', payment_frequency: 'MONTHLY',
        remaining_installments: 60, prepayment_charge_type: 'NONE',
        prepayment_charge_value: null, fees_or_penalties: null, next_payment_date: null,
        rate_reset_date: null
      }],
      monthlyIncome: money.money(10000000, 'INR'), currency: 'INR', now: NOW
    });

    const result = assessNetWorth({
      assetRows: [assetRow({ current_value: '800000.00' })],
      debtAssessment: debt, currency: 'INR', now: NOW
    });

    assert.strictEqual(result.totalAssets.minorUnits, 80000000);
    assert.strictEqual(result.totalLiabilities.minorUnits, 50000000);
    assert.strictEqual(result.netWorth.minorUnits, 30000000);
  });

  ok('every contributing holding is traceable', () => {
    const result = assessNetWorth({
      assetRows: [assetRow({ local_id: 3 }), assetRow({ local_id: 7, current_value: '50000.00' })],
      debtAssessment: null, currency: 'INR', now: NOW
    });

    assert.deepStrictEqual(result.provenance.sourceRecordIds, [3, 7]);
    assert.strictEqual(result.assets.length, 2);
  });

  ok('family-owned holdings are excluded and named', () => {
    // A share of somebody else's holding is not a figure this engine can
    // derive, so it is left out rather than counted at some invented fraction.
    const result = assessNetWorth({
      assetRows: [assetRow(), assetRow({ local_id: 2, ownership: OWNERSHIP.FAMILY })],
      debtAssessment: null, currency: 'INR', now: NOW
    });

    assert.strictEqual(result.totalAssets.minorUnits, 20000000);
    assert.strictEqual(result.excludedByOwnership.length, 1);
    assert.ok(result.caveats.some((caveat) => caveat.includes('family-owned')));
  });

  ok('composition by liquidity is reported, not just one total', () => {
    // Someone whose whole net worth is a house is in a different position to
    // someone holding the same total in deposits.
    const result = assessNetWorth({
      assetRows: [
        assetRow({ local_id: 1, current_value: '100000.00', liquidity_class: LIQUIDITY.LIQUID_CASH }),
        assetRow({ local_id: 2, current_value: '900000.00', liquidity_class: LIQUIDITY.PHYSICAL })
      ],
      debtAssessment: null, currency: 'INR', now: NOW
    });

    assert.strictEqual(result.byLiquidity[LIQUIDITY.LIQUID_CASH].minorUnits, 10000000);
    assert.strictEqual(result.byLiquidity[LIQUIDITY.PHYSICAL].minorUnits, 90000000);
  });

  ok('a stale valuation is reported, not dropped', () => {
    // A flat bought four years ago is still owned; the figure is just older.
    const result = assessNetWorth({
      assetRows: [assetRow({ valuation_date: NOW - 400 * 86_400_000 })],
      debtAssessment: null, currency: 'INR', now: NOW
    });

    assert.strictEqual(result.totalAssets.minorUnits, 20000000);
    assert.strictEqual(result.assets[0].isStale, true);
    assert.ok(result.caveats.some((caveat) => caveat.includes('six months')));
  });

  ok('an asset in another currency throws rather than being summed', () => {
    assert.throws(
      () => assessNetWorth({
        assetRows: [assetRow({ currency: 'USD' })],
        debtAssessment: null, currency: 'INR', now: NOW
      }),
      /USD/
    );
  });

  ok('no assets recorded says so rather than reporting zero as a finding', () => {
    const result = assessNetWorth({
      assetRows: [], debtAssessment: null, currency: 'INR', now: NOW
    });

    assert.ok(result.caveats.some((caveat) => caveat.includes('Nothing you own is recorded')));
  });

  console.log('\nwhat counts as reserve');

  ok('retirement money is excluded with its reason', () => {
    const result = reserveFor([
      asset({ local_id: 1, current_value: '100000.00' }),
      asset({
        local_id: 2, label: 'Provident fund', current_value: '900000.00',
        liquidity_class: LIQUIDITY.RETIREMENT_LOCKED
      })
    ]);

    assert.strictEqual(result.eligibleReserve.minorUnits, 10000000);
    assert.strictEqual(result.excludedHoldings.length, 1);
    assert.ok(result.excludedHoldings[0].reason.includes('penalty'));
  });

  ok('a deposit still inside its lock-in is excluded', () => {
    // A five-year tax-saving deposit is liquid eventually and not now.
    const result = reserveFor([
      asset({
        local_id: 1, label: 'Tax-saving FD', current_value: '150000.00',
        liquidity_class: LIQUIDITY.LIQUID_INVESTMENT,
        lock_in_until: NOW + 400 * 86_400_000
      })
    ]);

    assert.strictEqual(result.eligibleReserve.minorUnits, 0);
    assert.ok(result.excludedHoldings[0].reason.includes('Locked in'));
  });

  ok('a lock-in that has passed no longer excludes the holding', () => {
    const result = reserveFor([
      asset({
        local_id: 1, current_value: '150000.00',
        liquidity_class: LIQUIDITY.LIQUID_INVESTMENT,
        lock_in_until: NOW - 10 * 86_400_000
      })
    ]);

    assert.strictEqual(result.eligibleReserve.minorUnits, 15000000);
  });

  ok('property is excluded because it would have to be sold', () => {
    const result = reserveFor([
      asset({ local_id: 1, label: 'Flat', current_value: '5000000.00', liquidity_class: LIQUIDITY.PHYSICAL })
    ]);

    assert.strictEqual(result.eligibleReserve.minorUnits, 0);
    assert.ok(result.excludedHoldings[0].reason.includes('sold'));
  });

  ok('family money is not counted as the user\'s reserve', () => {
    const graded = eligibility(asset({ ownership: OWNERSHIP.FAMILY }), NOW);

    assert.strictEqual(graded.eligible, false);
    assert.ok(graded.reason.includes('family-owned'));
  });

  console.log('\ncoverage');

  ok('coverage is measured against essential spending, not all spending', () => {
    // The question is how long the lights stay on, not how long a life stays
    // unchanged. ₹30,000 essential a month against ₹1,80,000 held.
    const result = reserveFor([asset({ current_value: '180000.00' })]);

    assert.strictEqual(result.essentialMonthlySpend.minorUnits, 3000000);
    assert.strictEqual(result.coverageMonths, 6);
  });

  ok('discretionary spending does not raise what you are told to hold', () => {
    const rows = [...essentialSixMonths()];
    for (let month = 2; month <= 7; month += 1) {
      rows.push(expense(900 + month, 25000, 2026, month, 'Entertainment'));
    }
    const result = reserveFor([asset({ current_value: '180000.00' })], rows);

    assert.strictEqual(result.essentialMonthlySpend.minorUnits, 3000000);
  });

  ok('an EMI counts as essential whatever its category', () => {
    // A missed loan payment has consequences a missed restaurant meal does not.
    assert.strictEqual(
      countsAsEssential({ nature: 'LoanRepayment', category: 'Shopping' }),
      true
    );
  });

  ok('no essential spending recorded gives no coverage rather than infinity', () => {
    const result = reserveFor([asset({ current_value: '180000.00' })], []);

    assert.strictEqual(result.coverageMonths, null);
    assert.strictEqual(result.status, RESERVE_STATUS.UNKNOWN);
  });

  console.log('\nthe target, as a policy');

  ok('steadier income asks for a smaller buffer', () => {
    assert.ok(
      targetMonths({ incomeStability: STABILITY.STABLE, debtServiceRatio: null }).months <
      targetMonths({ incomeStability: STABILITY.VOLATILE, debtServiceRatio: null }).months
    );
  });

  ok('heavy debt asks for more months', () => {
    const light = targetMonths({ incomeStability: STABILITY.STABLE, debtServiceRatio: 0.1 });
    const heavy = targetMonths({ incomeStability: STABILITY.STABLE, debtServiceRatio: 0.5 });

    assert.strictEqual(heavy.months - light.months, 2);
    assert.ok(heavy.basedOn.some((note) => note.includes('extra months')));
  });

  ok('the target is versioned and says it is guidance', () => {
    const result = reserveFor([asset()]);

    assert.strictEqual(result.target.policyVersion, POLICY_VERSION);
    assert.ok(result.caveats.some((caveat) => caveat.includes("this app's guidance, not a rule")));
  });

  ok('status grades against the target rather than a fixed number', () => {
    // Five months of cover clears a stable earner's 4-month target and falls
    // short of a freelancer's 9-month one, on identical holdings.
    const held = [asset({ current_value: '150000.00' })];

    assert.strictEqual(reserveFor(held).status, RESERVE_STATUS.ADEQUATE);
    assert.strictEqual(
      reserveFor(held, essentialSixMonths(), { incomeStability: STABILITY.VOLATILE }).status,
      RESERVE_STATUS.NEEDS_ATTENTION
    );
  });

  ok('a shortfall says how much is missing', () => {
    const result = reserveFor([asset({ current_value: '30000.00' })]);

    // Target 4 months × ₹30,000 essential = ₹1,20,000, against ₹30,000 held.
    assert.strictEqual(result.requiredReserve.minorUnits, 12000000);
    assert.strictEqual(result.shortfall.minorUnits, 9000000);
    assert.strictEqual(result.status, RESERVE_STATUS.CRITICAL);
  });

  ok('the essential-split default is always disclosed', () => {
    const result = reserveFor([asset()]);

    assert.ok(result.caveats.some((caveat) => caveat.includes('not your')));
  });

  console.log('\nall net worth and reserve tests passed');
})();
