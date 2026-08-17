/**
 * What is owed, what it costs, and whether paying it down early is worth doing.
 *
 * The governing rule is that this engine would rather say nothing than say
 * something plausible. A loan whose rate the user never entered is a common
 * case — people know their EMI and not their APR — and the tempting response is
 * to assume a market rate and produce a confident interest figure. That figure
 * would be indistinguishable from a real one on screen and could talk somebody
 * into prepaying a loan when they should not. So an unknown term produces a null
 * and a stated reason, never an estimate dressed as a fact.
 *
 * Every projected figure carries the assumptions it rests on, because they are
 * doing real work: "remaining interest" assumes the schedule runs to term at the
 * current payment, which a variable rate can invalidate the moment it resets.
 *
 * V1 scope, stated rather than implied: this models a level-payment,
 * reducing-balance loan. It does not model step-up schedules, moratoria,
 * part-disbursement construction loans, or the effect of a rate reset it has not
 * been told the date of. Where the data cannot support the model, the confidence
 * drops and the figure is withheld.
 */
const money = require('./money');
const { provenance, SOURCE_TYPE } = require('./provenance');

const RATE_TYPE = Object.freeze({ FIXED: 'FIXED', VARIABLE: 'VARIABLE', UNKNOWN: 'UNKNOWN' });

const PREPAYMENT_CHARGE = Object.freeze({
  NONE: 'NONE',
  FLAT: 'FLAT',
  PERCENT_OF_PRINCIPAL: 'PERCENT_OF_PRINCIPAL',
  UNKNOWN: 'UNKNOWN'
});

/** How the debt load reads against income. Bands, not a bare ratio. */
const DEBT_LOAD = Object.freeze({
  LOW: 'LOW',
  MODERATE: 'MODERATE',
  HIGH: 'HIGH',
  UNKNOWN: 'UNKNOWN'
});

/**
 * Thresholds for the bands above, as a share of gross monthly income.
 *
 * Round numbers, and deliberately so: they are a presentational convention this
 * product has chosen, not a regulatory or actuarial standard, and dressing them
 * up with decimal places would imply a precision nobody has established. Lenders
 * commonly treat total obligations above roughly half of income as a serious
 * constraint, which is where HIGH begins.
 */
const MODERATE_ABOVE = 0.20;
const HIGH_ABOVE = 0.40;

/** Payments per year, for normalising a schedule to a monthly figure. */
const PAYMENTS_PER_YEAR = Object.freeze({
  WEEKLY: 52, BIWEEKLY: 26, MONTHLY: 12, QUARTERLY: 4, YEARLY: 1
});

/** A projection reaching past this many periods is reported as not terminating. */
const MAX_PROJECTED_PERIODS = 1200;

/**
 * The periodic rate a schedule actually charges.
 *
 * Nominal annual rates are quoted per year and applied per period, so a 12%
 * loan paid monthly charges 1% a month. Annual compounding is converted
 * geometrically rather than divided — dividing would overstate the periodic
 * cost on a loan that only compounds once a year.
 */
function periodicRate(annualPercent, compounding, paymentsPerYear) {
  if (annualPercent === null || annualPercent === undefined) return null;

  const annual = Number(annualPercent) / 100;
  if (!Number.isFinite(annual) || annual < 0) return null;
  if (annual === 0) return 0;

  if (compounding === 'ANNUAL') {
    return (1 + annual) ** (1 / paymentsPerYear) - 1;
  }
  // MONTHLY and UNKNOWN both take the simple division. For UNKNOWN this is an
  // assumption, and every result built on it says so.
  return annual / paymentsPerYear;
}

/**
 * How many payments clear a balance, or null when they never would.
 *
 * A payment at or below the interest accruing each period never reduces the
 * principal. Returning a very large number there would be arithmetically
 * defensible and practically a lie — the loan does not have a long payoff, it
 * has no payoff — so it returns null with that stated as the reason.
 */
function periodsToClear(principalMinor, rate, paymentMinor) {
  if (paymentMinor <= 0) return null;
  if (rate === null) return null;

  if (rate === 0) return Math.ceil(principalMinor / paymentMinor);

  const interestPerPeriod = principalMinor * rate;
  if (paymentMinor <= interestPerPeriod) return null;

  const periods = -Math.log(1 - (principalMinor * rate) / paymentMinor) / Math.log(1 + rate);
  if (!Number.isFinite(periods) || periods > MAX_PROJECTED_PERIODS) return null;

  return Math.ceil(periods);
}

/**
 * Total interest still to be paid, when the schedule is known well enough.
 *
 * Two routes, and which one is used changes what the figure means:
 *
 * Given a payment and a remaining count, the answer is arithmetic — what will be
 * paid, less what is owed — and holds regardless of the rate. Given a payment and
 * a rate but no count, the term has to be derived from the rate first, which
 * makes the result only as good as the rate.
 *
 * With neither, there is nothing to compute and it returns null. That is the
 * case this function exists to handle honestly.
 */
function remainingInterest(loan) {
  const { principal, payment, remainingInstallments, rate } = loan;

  if (!payment || payment.minorUnits <= 0) {
    return { amount: null, basis: null, reason: 'No scheduled payment recorded.' };
  }

  if (Number.isInteger(remainingInstallments) && remainingInstallments > 0) {
    const totalToPay = money.multiply(payment, remainingInstallments);
    const interest = money.subtract(totalToPay, principal);
    return {
      amount: money.coerceAtLeastZero(interest),
      basis: 'SCHEDULE',
      reason: null,
      assumptions: [
        'Assumes the loan runs to term at the payment recorded.',
        ...(money.isNegative(interest)
          ? ['The payments recorded total less than the balance, so interest reads as nil.']
          : [])
      ]
    };
  }

  if (rate === null) {
    return {
      amount: null,
      basis: null,
      reason:
        'Neither the remaining instalments nor the interest rate is recorded, ' +
        'so the remaining interest cannot be worked out.'
    };
  }

  const periods = periodsToClear(principal.minorUnits, rate, payment.minorUnits);
  if (periods === null) {
    return {
      amount: null,
      basis: null,
      reason:
        'At this payment the balance would not clear — the payment does not ' +
        'cover the interest accruing.'
    };
  }

  const interest = money.subtract(money.multiply(payment, periods), principal);
  return {
    amount: money.coerceAtLeastZero(interest),
    basis: 'DERIVED_FROM_RATE',
    reason: null,
    assumptions: [
      `Assumes ${periods} remaining payments, derived from the rate recorded.`,
      'Assumes the rate does not change.'
    ]
  };
}

/**
 * What paying a lump sum off now would save.
 *
 * The charge for doing so is subtracted where it is known and refused where it
 * is not. An unknown penalty is the difference between a saving and a loss, and
 * a "you would save ₹X" that silently omits a 2% fee is exactly the kind of
 * confident, wrong figure that costs somebody money.
 */
function prepaymentScenario(loan, lumpSum) {
  const { principal, payment, rate, prepaymentChargeType, prepaymentChargeValue } = loan;

  if (rate === null) {
    return {
      viable: false,
      reason:
        'Without the interest rate there is no way to work out what paying early ' +
        'would save.'
    };
  }
  if (!payment || payment.minorUnits <= 0) {
    return { viable: false, reason: 'No scheduled payment recorded.' };
  }
  if (lumpSum.minorUnits <= 0) {
    return { viable: false, reason: 'Nothing to pay down.' };
  }

  const applied = money.compare(lumpSum, principal) > 0 ? principal : lumpSum;
  const before = periodsToClear(principal.minorUnits, rate, payment.minorUnits);
  const remaining = money.subtract(principal, applied);
  const after = money.isZero(remaining)
    ? 0
    : periodsToClear(remaining.minorUnits, rate, payment.minorUnits);

  if (before === null || after === null) {
    return {
      viable: false,
      reason: 'At this payment the balance would not clear, so there is nothing to compare.'
    };
  }

  const interestBefore = money.subtract(money.multiply(payment, before), principal);
  const interestAfter = after === 0
    ? money.zero(principal.currency)
    : money.subtract(money.multiply(payment, after), remaining);
  const grossSaving = money.subtract(interestBefore, interestAfter);

  const charge = prepaymentCharge(applied, prepaymentChargeType, prepaymentChargeValue);

  return {
    viable: true,
    lumpSum: applied,
    periodsSaved: before - after,
    interestSavedBeforeCharges: money.coerceAtLeastZero(grossSaving),
    charge: charge.amount,
    // Null, not the gross figure, when the charge is unknown. Presenting the
    // gross saving as the net one is the error this guards against.
    netSaving: charge.known
      ? money.subtract(money.coerceAtLeastZero(grossSaving), charge.amount)
      : null,
    chargeKnown: charge.known,
    assumptions: [
      'Assumes the payment stays the same and the term shortens.',
      'Assumes the rate does not change.',
      ...(charge.known
        ? []
        : ['The early-repayment charge is not recorded, so the net benefit is not stated.'])
    ]
  };
}

function prepaymentCharge(amount, type, value) {
  switch (type) {
    case PREPAYMENT_CHARGE.NONE:
      return { known: true, amount: money.zero(amount.currency) };
    case PREPAYMENT_CHARGE.FLAT:
      return value === null || value === undefined
        ? { known: false, amount: null }
        : { known: true, amount: money.fromLegacyDouble(Number(value), amount.currency) };
    case PREPAYMENT_CHARGE.PERCENT_OF_PRINCIPAL:
      return value === null || value === undefined
        ? { known: false, amount: null }
        : { known: true, amount: money.scaleBy(amount, Number(value) / 100) };
    default:
      return { known: false, amount: null };
  }
}

/** Normalises a row from the loan_details table into the shape used above. */
function readLoan(row, currency) {
  const paymentsPerYear = PAYMENTS_PER_YEAR[row.payment_frequency] ?? 12;

  return {
    recurringLocalId: row.recurring_local_id,
    currency,
    principal: money.fromDecimalString(row.principal_outstanding, currency),
    outstandingAsOf: Number(row.outstanding_as_of),
    payment: row.scheduled_payment === null || row.scheduled_payment === undefined
      ? null
      : money.fromDecimalString(row.scheduled_payment, currency),
    paymentsPerYear,
    remainingInstallments: row.remaining_installments === null ? null : Number(row.remaining_installments),
    rateType: row.rate_type ?? RATE_TYPE.UNKNOWN,
    rate: periodicRate(row.interest_rate, row.interest_compounding, paymentsPerYear),
    annualRatePercent: row.interest_rate === null || row.interest_rate === undefined
      ? null
      : Number(row.interest_rate),
    rateResetDate: row.rate_reset_date ?? null,
    prepaymentChargeType: row.prepayment_charge_type ?? PREPAYMENT_CHARGE.UNKNOWN,
    prepaymentChargeValue: row.prepayment_charge_value ?? null,
    feesOrPenalties: row.fees_or_penalties ?? null
  };
}

/** What one loan costs each month, whatever its payment frequency. */
function monthlyServiceOf(loan) {
  if (!loan.payment) return money.zero(loan.currency);
  return money.scaleBy(loan.payment, loan.paymentsPerYear / 12);
}

/**
 * How confident the engine is about one loan's figures.
 *
 * Reported per loan rather than once for the whole assessment, because a user
 * with a well-documented mortgage and a vaguely-remembered car loan deserves to
 * see which of the two is holding the answer back.
 */
function confidenceFor(loan, now) {
  const gaps = [];

  if (loan.rateType === RATE_TYPE.UNKNOWN || loan.rate === null) {
    gaps.push('the interest rate is not recorded');
  }
  if (!loan.payment) gaps.push('the scheduled payment is not recorded');
  if (!Number.isInteger(loan.remainingInstallments)) {
    gaps.push('the number of payments left is not recorded');
  }
  if (loan.rateType === RATE_TYPE.VARIABLE) {
    gaps.push('the rate is variable and may change');
  }
  if (loan.prepaymentChargeType === PREPAYMENT_CHARGE.UNKNOWN) {
    gaps.push('any early-repayment charge is not recorded');
  }

  const ageDays = (now - loan.outstandingAsOf) / 86_400_000;
  if (ageDays > 180) gaps.push('the balance was last updated over six months ago');

  const level = gaps.length === 0 ? 'HIGH' : (gaps.length <= 2 ? 'MEDIUM' : 'LOW');
  return { level, gaps };
}

function bandFor(ratio) {
  if (ratio === null) return DEBT_LOAD.UNKNOWN;
  if (ratio > HIGH_ABOVE) return DEBT_LOAD.HIGH;
  if (ratio > MODERATE_ABOVE) return DEBT_LOAD.MODERATE;
  return DEBT_LOAD.LOW;
}

/**
 * The debt assessment.
 *
 * @param loanRows        rows from loan_details, already scoped to `currency`
 * @param monthlyIncome   the cash-flow engine's income baseline, or null
 * @param currency        analysis currency
 * @param now             epoch millis
 */
function assessDebt({ loanRows, monthlyIncome, currency, now }) {
  const loans = loanRows.map((row) => {
    if (row.currency !== currency) {
      throw new Error(
        `Loan ${row.recurring_local_id} is denominated in ${row.currency} but the ` +
        `assessment is in ${currency}; this product has no exchange rate source.`
      );
    }
    return readLoan(row, currency);
  });

  const assessed = loans.map((loan) => {
    const interest = remainingInterest(loan);
    return {
      recurringLocalId: loan.recurringLocalId,
      principalOutstanding: loan.principal,
      outstandingAsOf: loan.outstandingAsOf,
      monthlyService: monthlyServiceOf(loan),
      annualRatePercent: loan.annualRatePercent,
      rateType: loan.rateType,
      remainingInstallments: loan.remainingInstallments,
      remainingInterest: interest.amount,
      remainingInterestBasis: interest.basis,
      // Present whenever the figure is withheld, so a blank is never unexplained.
      remainingInterestUnavailableBecause: interest.reason,
      assumptions: interest.assumptions ?? [],
      confidence: confidenceFor(loan, now)
    };
  });

  const totalPrincipal = money.sum(assessed.map((entry) => entry.principalOutstanding), currency);
  const totalMonthlyService = money.sum(assessed.map((entry) => entry.monthlyService), currency);

  // Only summed when every loan could produce one. A partial total would read as
  // the whole cost of someone's borrowing while silently omitting a loan.
  const everyInterestKnown = assessed.length > 0 &&
    assessed.every((entry) => entry.remainingInterest !== null);
  const totalRemainingInterest = everyInterestKnown
    ? money.sum(assessed.map((entry) => entry.remainingInterest), currency)
    : null;

  const ratio = monthlyIncome && !money.isZero(monthlyIncome)
    ? money.ratio(totalMonthlyService, monthlyIncome)
    : null;

  return {
    currency,
    loans: assessed,
    totalPrincipalOutstanding: totalPrincipal,
    totalMonthlyService,
    totalRemainingInterest,
    totalRemainingInterestPartial: !everyInterestKnown && assessed.length > 0,
    debtServiceRatio: ratio,
    debtLoad: bandFor(ratio),
    provenance: provenance({
      sourceType: SOURCE_TYPE.CONFIRMED,
      sourceRecordIds: loans.map((loan) => loan.recurringLocalId),
      asOf: now
    }),
    caveats: caveatsFor(assessed, ratio, monthlyIncome)
  };
}

function caveatsFor(assessed, ratio, monthlyIncome) {
  const caveats = [];

  if (assessed.length === 0) {
    caveats.push('No loan terms are recorded, so nothing can be said about borrowing costs.');
    return caveats;
  }

  if (!monthlyIncome || money.isZero(monthlyIncome)) {
    caveats.push(
      'No income baseline is available, so debt cannot be measured against what you earn.'
    );
  } else if (ratio !== null && ratio > HIGH_ABOVE) {
    caveats.push(
      `Loan payments take about ${Math.round(ratio * 100)}% of your monthly income.`
    );
  }

  const withoutInterest = assessed.filter((entry) => entry.remainingInterest === null);
  if (withoutInterest.length > 0) {
    caveats.push(
      `${withoutInterest.length} loan(s) do not have enough recorded to work out ` +
      'what the borrowing will cost. Adding the rate and the number of payments ' +
      'left would answer it.'
    );
  }

  const variable = assessed.filter((entry) => entry.rateType === RATE_TYPE.VARIABLE);
  if (variable.length > 0) {
    caveats.push(
      `${variable.length} loan(s) have a variable rate. Any figure here assumes ` +
      'the rate stays where it is.'
    );
  }

  return caveats;
}

module.exports = {
  assessDebt,
  readLoan,
  remainingInterest,
  prepaymentScenario,
  prepaymentCharge,
  periodsToClear,
  periodicRate,
  monthlyServiceOf,
  confidenceFor,
  bandFor,
  RATE_TYPE,
  PREPAYMENT_CHARGE,
  DEBT_LOAD,
  MODERATE_ABOVE,
  HIGH_ABOVE,
  PAYMENTS_PER_YEAR
};
