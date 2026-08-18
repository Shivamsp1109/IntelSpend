/**
 * Never let a candidate compare or combine amounts in different currencies.
 *
 * The product has no exchange-rate source, and the failure mode here is
 * particularly quiet: a candidate denominated in one currency measured against a
 * surplus in another produces a comparison that is arithmetically valid and
 * factually meaningless. Nothing on screen would look wrong.
 *
 * The engines below already refuse to add mismatched money, so this constraint
 * is not the only line of defence — it is the one that turns a thrown exception
 * into a rejected candidate with a reason the user can read. Depending on an
 * exception for correctness works until somebody wraps a call in a try/catch.
 */
module.exports = {
  id: 'NO_CURRENCY_MIXING',
  version: '1.0.0',

  /** Every candidate carrying an amount. */
  appliesTo: (candidate) => Boolean(candidate.amount || candidate.monthlyAmount),

  evaluate(candidate, context) {
    const analysisCurrency = context.currency;
    const mismatched = [];

    for (const field of ['amount', 'monthlyAmount']) {
      const value = candidate[field];
      if (value && value.currency !== analysisCurrency) {
        mismatched.push(`${field} in ${value.currency}`);
      }
    }

    if (mismatched.length > 0) {
      return {
        passed: false,
        reason: 'CURRENCY_MISMATCH',
        detail:
          `This assessment is in ${analysisCurrency} but the suggestion carries ` +
          `${mismatched.join(' and ')}. There is no exchange rate source here, ` +
          'so the two are not comparable.'
      };
    }

    return { passed: true, reason: null, detail: null };
  }
};
