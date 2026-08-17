/**
 * The debt engine.
 *
 * Run with: npm test
 *
 * The failure this exists to prevent is a confident interest figure built on a
 * rate nobody entered. It would be indistinguishable from a real one on screen,
 * and it could talk someone into prepaying a loan when they should not. So the
 * tests below care as much about what the engine refuses to say as about what
 * it computes.
 */
const assert = require('assert');
const money = require('../src/engine/money');
const {
  assessDebt,
  readLoan,
  remainingInterest,
  prepaymentScenario,
  periodsToClear,
  periodicRate,
  monthlyServiceOf,
  bandFor,
  DEBT_LOAD
} = require('../src/engine/debtEngine');

function ok(name, fn) {
  fn();
  console.log(`  ok  ${name}`);
}

const NOW = Date.UTC(2026, 7, 17);

const loanRow = (overrides = {}) => ({
  recurring_local_id: 1,
  principal_outstanding: '500000.00',
  outstanding_as_of: NOW - 10 * 86_400_000,
  currency: 'INR',
  interest_rate: '9.0000',
  rate_type: 'FIXED',
  rate_reset_date: null,
  interest_compounding: 'MONTHLY',
  scheduled_payment: '10000.00',
  payment_frequency: 'MONTHLY',
  remaining_installments: 60,
  next_payment_date: NOW + 10 * 86_400_000,
  prepayment_charge_type: 'NONE',
  prepayment_charge_value: null,
  fees_or_penalties: null,
  ...overrides
});

const read = (overrides) => readLoan(loanRow(overrides), 'INR');

(async () => {
  console.log('refusing to invent a schedule');

  ok('an unknown rate with no instalment count yields no interest figure', () => {
    // The case the whole engine is shaped around. People know their EMI and not
    // their APR, and a plausible assumed rate would produce a number nobody
    // could tell from a real one.
    const result = remainingInterest(read({
      interest_rate: null, rate_type: 'UNKNOWN', remaining_installments: null
    }));

    assert.strictEqual(result.amount, null);
    assert.ok(result.reason.includes('cannot be worked out'));
  });

  ok('the blank is always explained', () => {
    const result = remainingInterest(read({ scheduled_payment: null }));

    assert.strictEqual(result.amount, null);
    assert.ok(result.reason && result.reason.length > 0);
  });

  ok('an unknown rate still yields interest when the schedule is known', () => {
    // Payments times count, less the balance, is arithmetic — it holds whatever
    // the rate is, so withholding it here would be over-cautious.
    const result = remainingInterest(read({
      interest_rate: null, rate_type: 'UNKNOWN', remaining_installments: 60
    }));

    // 60 × ₹10,000 = ₹6,00,000 against a ₹5,00,000 balance.
    assert.strictEqual(result.amount.minorUnits, 10000000);
    assert.strictEqual(result.basis, 'SCHEDULE');
  });

  ok('a known rate derives the term when the count is missing', () => {
    const result = remainingInterest(read({ remaining_installments: null }));

    assert.strictEqual(result.basis, 'DERIVED_FROM_RATE');
    assert.ok(result.amount.minorUnits > 0);
    assert.ok(result.assumptions.some((note) => note.includes('does not change')));
  });

  ok('a payment that never clears the balance is refused, not projected', () => {
    // ₹3,000 against ₹5,00,000 at 9% does not even cover the interest. A very
    // large month count would be arithmetically defensible and a practical lie.
    const result = remainingInterest(read({
      scheduled_payment: '3000.00', remaining_installments: null
    }));

    assert.strictEqual(result.amount, null);
    assert.ok(result.reason.includes('does not'));
  });

  ok('periodsToClear returns null rather than a huge number', () => {
    assert.strictEqual(periodsToClear(500000_00, 0.0075, 3000_00), null);
  });

  console.log('\ninterest arithmetic');

  ok('an interest-free loan clears by division', () => {
    assert.strictEqual(periodsToClear(100000, 0, 10000), 10);
  });

  ok('annual compounding is converted geometrically, not divided', () => {
    // Dividing would overstate the monthly cost of a loan that compounds once
    // a year.
    const annual = periodicRate(12, 'ANNUAL', 12);
    const monthly = periodicRate(12, 'MONTHLY', 12);

    assert.ok(annual < monthly, 'annual compounding should give a lower periodic rate');
    assert.ok(Math.abs((1 + annual) ** 12 - 1.12) < 1e-9);
  });

  ok('an unparseable rate is treated as unknown rather than zero', () => {
    // Zero would quietly turn a real loan into an interest-free one.
    assert.strictEqual(periodicRate(null, 'MONTHLY', 12), null);
    assert.strictEqual(periodicRate(-5, 'MONTHLY', 12), null);
  });

  console.log('\npayment frequency');

  ok('a quarterly schedule is normalised to a monthly cost', () => {
    const loan = read({ scheduled_payment: '30000.00', payment_frequency: 'QUARTERLY' });

    assert.strictEqual(monthlyServiceOf(loan).minorUnits, 1000000);
  });

  ok('a weekly schedule uses 52 payments a year, not 48', () => {
    const loan = read({ scheduled_payment: '1000.00', payment_frequency: 'WEEKLY' });

    // 1000 × 52 / 12 = 4333.33
    assert.strictEqual(monthlyServiceOf(loan).minorUnits, 433333);
  });

  console.log('\nprepayment');

  ok('paying down early shortens the term and saves interest', () => {
    const result = prepaymentScenario(read(), money.money(10000000, 'INR'));

    assert.strictEqual(result.viable, true);
    assert.ok(result.periodsSaved > 0);
    assert.ok(result.interestSavedBeforeCharges.minorUnits > 0);
  });

  ok('an unknown early-repayment charge refuses to state a net benefit', () => {
    // "You would save ₹X" that silently omits a 2% fee is exactly the confident,
    // wrong figure that costs somebody money.
    const result = prepaymentScenario(
      read({ prepayment_charge_type: 'UNKNOWN' }),
      money.money(10000000, 'INR')
    );

    assert.ok(result.interestSavedBeforeCharges.minorUnits > 0);
    assert.strictEqual(result.netSaving, null);
    assert.strictEqual(result.chargeKnown, false);
    assert.ok(result.assumptions.some((note) => note.includes('not recorded')));
  });

  ok('a percentage charge is netted off when it is known', () => {
    const result = prepaymentScenario(
      read({ prepayment_charge_type: 'PERCENT_OF_PRINCIPAL', prepayment_charge_value: '2.0000' }),
      money.money(10000000, 'INR')
    );

    assert.strictEqual(result.charge.minorUnits, 200000);
    assert.strictEqual(
      result.netSaving.minorUnits,
      result.interestSavedBeforeCharges.minorUnits - 200000
    );
  });

  ok('without a rate there is nothing to compare', () => {
    const result = prepaymentScenario(
      read({ interest_rate: null, rate_type: 'UNKNOWN' }),
      money.money(10000000, 'INR')
    );

    assert.strictEqual(result.viable, false);
    assert.ok(result.reason.includes('interest rate'));
  });

  ok('a lump sum larger than the balance is capped at the balance', () => {
    const result = prepaymentScenario(read(), money.money(99999999, 'INR'));

    assert.strictEqual(result.lumpSum.minorUnits, 50000000);
  });

  console.log('\nthe whole assessment');

  ok('totals and the debt-service ratio come out of the loans', () => {
    const result = assessDebt({
      loanRows: [loanRow(), loanRow({ recurring_local_id: 2, scheduled_payment: '5000.00' })],
      monthlyIncome: money.money(10000000, 'INR'),
      currency: 'INR',
      now: NOW
    });

    assert.strictEqual(result.totalMonthlyService.minorUnits, 1500000);
    assert.strictEqual(result.totalPrincipalOutstanding.minorUnits, 100000000);
    assert.strictEqual(result.debtServiceRatio, 0.15);
    assert.strictEqual(result.debtLoad, DEBT_LOAD.LOW);
  });

  ok('a total interest figure is withheld when any single loan is unknown', () => {
    // A partial total would read as the whole cost of someone's borrowing while
    // silently omitting a loan.
    const result = assessDebt({
      loanRows: [
        loanRow(),
        loanRow({
          recurring_local_id: 2, interest_rate: null,
          rate_type: 'UNKNOWN', remaining_installments: null
        })
      ],
      monthlyIncome: money.money(10000000, 'INR'),
      currency: 'INR',
      now: NOW
    });

    assert.strictEqual(result.totalRemainingInterest, null);
    assert.strictEqual(result.totalRemainingInterestPartial, true);
    assert.ok(result.caveats.some((caveat) => caveat.includes('do not have enough recorded')));
  });

  ok('confidence is reported per loan, naming what is missing', () => {
    const result = assessDebt({
      loanRows: [loanRow({ interest_rate: null, rate_type: 'UNKNOWN' })],
      monthlyIncome: money.money(10000000, 'INR'),
      currency: 'INR',
      now: NOW
    });

    const loan = result.loans[0];
    assert.notStrictEqual(loan.confidence.level, 'HIGH');
    assert.ok(loan.confidence.gaps.some((gap) => gap.includes('interest rate')));
  });

  ok('a stale balance lowers confidence', () => {
    const result = assessDebt({
      loanRows: [loanRow({ outstanding_as_of: NOW - 300 * 86_400_000 })],
      monthlyIncome: money.money(10000000, 'INR'),
      currency: 'INR',
      now: NOW
    });

    assert.ok(result.loans[0].confidence.gaps.some((gap) => gap.includes('six months')));
  });

  ok('a variable rate is flagged on every figure it touches', () => {
    const result = assessDebt({
      loanRows: [loanRow({ rate_type: 'VARIABLE' })],
      monthlyIncome: money.money(10000000, 'INR'),
      currency: 'INR',
      now: NOW
    });

    assert.ok(result.caveats.some((caveat) => caveat.includes('variable rate')));
  });

  ok('no income means no ratio rather than a divide by zero', () => {
    const result = assessDebt({
      loanRows: [loanRow()], monthlyIncome: null, currency: 'INR', now: NOW
    });

    assert.strictEqual(result.debtServiceRatio, null);
    assert.strictEqual(result.debtLoad, DEBT_LOAD.UNKNOWN);
  });

  ok('a heavy debt load is banded as high', () => {
    assert.strictEqual(bandFor(0.55), DEBT_LOAD.HIGH);
    assert.strictEqual(bandFor(0.30), DEBT_LOAD.MODERATE);
    assert.strictEqual(bandFor(0.10), DEBT_LOAD.LOW);
  });

  ok('a loan in another currency throws rather than being summed', () => {
    assert.throws(
      () => assessDebt({
        loanRows: [loanRow({ currency: 'USD' })],
        monthlyIncome: money.money(10000000, 'INR'),
        currency: 'INR',
        now: NOW
      }),
      /USD/
    );
  });

  ok('no loans at all says so rather than reporting zero debt as a finding', () => {
    const result = assessDebt({
      loanRows: [], monthlyIncome: money.money(10000000, 'INR'), currency: 'INR', now: NOW
    });

    assert.strictEqual(result.debtLoad, DEBT_LOAD.LOW);
    assert.ok(result.caveats.some((caveat) => caveat.includes('No loan terms are recorded')));
  });

  console.log('\nall debt engine tests passed');
})();
