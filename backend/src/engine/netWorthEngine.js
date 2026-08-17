/**
 * What is owned, less what is owed.
 *
 * The simplest arithmetic in the engine and the easiest to quietly get wrong,
 * because everything depends on what is allowed into each side. Counting a
 * spouse's account inflates it, counting a credit-card balance twice deflates
 * it, and either result looks like a plausible number.
 *
 * Every contributing row is named in the result, so a figure someone disputes
 * can be taken apart rather than argued with.
 *
 * Assets valued long ago are included but reported as stale rather than dropped.
 * A flat bought four years ago is still owned; the figure is simply older than
 * the others, and the honest response is to say so, not to pretend the asset
 * does not exist.
 */
const money = require('./money');
const { provenance, SOURCE_TYPE } = require('./provenance');

const LIQUIDITY = Object.freeze({
  LIQUID_CASH: 'LIQUID_CASH',
  LIQUID_INVESTMENT: 'LIQUID_INVESTMENT',
  ILLIQUID_INVESTMENT: 'ILLIQUID_INVESTMENT',
  PHYSICAL: 'PHYSICAL',
  RETIREMENT_LOCKED: 'RETIREMENT_LOCKED'
});

const OWNERSHIP = Object.freeze({ SELF: 'SELF', JOINT: 'JOINT', FAMILY: 'FAMILY' });

/** A valuation older than this is reported as stale, not discarded. */
const STALE_VALUATION_DAYS = 180;

function readAsset(row, currency) {
  if (row.currency !== currency) {
    throw new Error(
      `Asset '${row.label}' is held in ${row.currency} but the assessment is in ` +
      `${currency}; this product has no exchange rate source.`
    );
  }

  return {
    localId: row.local_id,
    label: row.label,
    assetType: row.asset_type,
    value: money.fromDecimalString(row.current_value, currency),
    valuationDate: Number(row.valuation_date),
    liquidityClass: row.liquidity_class,
    lockInUntil: row.lock_in_until ?? null,
    ownership: row.ownership,
    verificationSource: row.verification_source
  };
}

/**
 * Net worth for one user.
 *
 * @param assetRows       rows from `assets`, scoped to `currency`
 * @param debtAssessment  the debt engine's result, or null when no loans exist
 * @param currency        analysis currency
 * @param now             epoch millis
 */
function assessNetWorth({ assetRows, debtAssessment, currency, now }) {
  const assets = assetRows.map((row) => readAsset(row, currency));

  // Family money the user cannot unilaterally spend is excluded from the total
  // rather than counted at some fraction. A share of somebody else's holding is
  // not a figure this engine can derive, and inventing one would be worse than
  // leaving it out and saying so.
  const owned = assets.filter((asset) => asset.ownership !== OWNERSHIP.FAMILY);
  const excludedByOwnership = assets.filter((asset) => asset.ownership === OWNERSHIP.FAMILY);

  const totalAssets = money.sum(owned.map((asset) => asset.value), currency);
  const totalLiabilities = debtAssessment
    ? debtAssessment.totalPrincipalOutstanding
    : money.zero(currency);

  const stale = owned.filter(
    (asset) => (now - asset.valuationDate) / 86_400_000 > STALE_VALUATION_DAYS
  );

  return {
    currency,
    totalAssets,
    totalLiabilities,
    netWorth: money.subtract(totalAssets, totalLiabilities),

    // Composition rather than one figure: someone whose whole net worth is a
    // house is in a different position to someone holding the same total in
    // deposits, and a single number hides that entirely.
    byLiquidity: Object.fromEntries(
      Object.values(LIQUIDITY).map((liquidityClass) => [
        liquidityClass,
        money.sum(
          owned.filter((asset) => asset.liquidityClass === liquidityClass).map((a) => a.value),
          currency
        )
      ])
    ),

    assets: owned.map((asset) => ({
      localId: asset.localId,
      label: asset.label,
      assetType: asset.assetType,
      value: asset.value,
      liquidityClass: asset.liquidityClass,
      ownership: asset.ownership,
      valuationDate: asset.valuationDate,
      isStale: stale.includes(asset)
    })),

    excludedByOwnership: excludedByOwnership.map((asset) => ({
      localId: asset.localId,
      label: asset.label,
      reason: 'Recorded as family-owned, so it is not counted as yours.'
    })),

    provenance: provenance({
      sourceType: SOURCE_TYPE.CONFIRMED,
      sourceRecordIds: owned.map((asset) => asset.localId),
      asOf: now
    }),

    caveats: caveatsFor({ assets, owned, stale, excludedByOwnership, debtAssessment })
  };
}

function caveatsFor({ assets, owned, stale, excludedByOwnership, debtAssessment }) {
  const caveats = [];

  if (assets.length === 0) {
    caveats.push(
      'Nothing you own is recorded yet, so net worth is only what you owe. ' +
      'Adding your accounts and holdings would complete it.'
    );
  }

  if (!debtAssessment || debtAssessment.loans.length === 0) {
    caveats.push(
      'No loan balances are recorded, so this counts what you own against nothing owed.'
    );
  }

  if (stale.length > 0) {
    caveats.push(
      `${stale.length} holding(s) were last valued over six months ago and may ` +
      'have moved since.'
    );
  }

  if (excludedByOwnership.length > 0) {
    caveats.push(
      `${excludedByOwnership.length} holding(s) recorded as family-owned are not ` +
      'counted towards your net worth.'
    );
  }

  const estimated = owned.filter((asset) => asset.verificationSource === 'MANUAL');
  if (estimated.length > 0 && estimated.length === owned.length && owned.length > 0) {
    caveats.push('Every holding here is a figure you entered yourself rather than a confirmed balance.');
  }

  return caveats;
}

module.exports = {
  assessNetWorth,
  readAsset,
  LIQUIDITY,
  OWNERSHIP,
  STALE_VALUATION_DAYS
};
