/**
 * How what the user holds is spread, and whether it suits them.
 *
 * "Suits them" is doing a lot of work in that sentence, and this engine is
 * careful about it. Whether a portfolio is right for somebody depends on their
 * risk profile, and a profile that has not been completed and confirmed is not
 * something to guess at — so alignment reports UNKNOWN until it exists, rather
 * than defaulting to a middling assumption and grading against that.
 *
 * Concentration, by contrast, is observable without knowing anything about the
 * person: a holding that is most of what somebody owns is a concentration
 * whether they are cautious or bold. So that part is reported regardless, and
 * alignment is withheld separately.
 *
 * What this deliberately does not do is recommend securities, rebalance, or
 * suggest specific products. It describes a shape. Turning that into "you should
 * buy X" is regulated advice in India and needs a licence this product does not
 * have.
 */
const money = require('./money');
const { provenance, SOURCE_TYPE } = require('./provenance');
const { LIQUIDITY, OWNERSHIP } = require('./netWorthEngine');
const { RISK_LEVEL } = require('./riskProfile');

const PORTFOLIO_STATUS = Object.freeze({
  ALIGNED: 'ALIGNED',
  REVIEW: 'REVIEW',
  CONCENTRATED: 'CONCENTRATED',
  UNKNOWN: 'UNKNOWN'
});

/** A single holding above this share of the portfolio is a concentration. */
const CONCENTRATION_THRESHOLD = 0.40;

/**
 * How growth-oriented each asset type is.
 *
 * Broad buckets rather than expected returns, deliberately. "Equity is
 * growth-oriented" is a description of the instrument; "equity returns 12%" is a
 * forecast, and this product does not make those.
 */
const GROWTH_ORIENTED = Object.freeze([
  'STOCK', 'ETF', 'MUTUAL_FUND', 'CRYPTO'
]);

const DEFENSIVE = Object.freeze([
  'CASH', 'BANK_ACCOUNT', 'FIXED_DEPOSIT', 'RECURRING_DEPOSIT', 'BOND'
]);

/**
 * The share of growth-oriented holdings each risk level would suggest.
 *
 * Wide bands with a tolerance either side, because the precision is not there:
 * the difference between 58% and 62% equity is not a finding, and a narrow
 * target would generate a "review" every time a market moved.
 */
const SUGGESTED_GROWTH_SHARE = Object.freeze({
  [RISK_LEVEL.LOW]: { min: 0.0, max: 0.35 },
  [RISK_LEVEL.MODERATE]: { min: 0.25, max: 0.65 },
  [RISK_LEVEL.HIGH]: { min: 0.50, max: 0.90 }
});

const POLICY_VERSION = 'portfolio-policy-1.0.0';

/**
 * Assesses the shape of what is held.
 *
 * @param assets       already read via netWorthEngine.readAsset
 * @param riskProfile  from readRiskProfile — may be unknown, and often is
 */
function assessPortfolio({ assets, riskProfile, currency, now }) {
  // Property and vehicles are excluded from the investment mix: a home is
  // somewhere to live, and counting it as a holding would make almost every
  // household look overwhelmingly concentrated in one illiquid asset.
  const investable = assets.filter(
    (asset) => asset.ownership !== OWNERSHIP.FAMILY &&
      asset.liquidityClass !== LIQUIDITY.PHYSICAL
  );

  if (investable.length === 0) {
    return {
      currency,
      status: PORTFOLIO_STATUS.UNKNOWN,
      total: money.zero(currency),
      growthShare: null,
      concentrations: [],
      alignment: {
        status: PORTFOLIO_STATUS.UNKNOWN,
        note: 'Nothing investable is recorded, so there is no mix to assess.'
      },
      policyVersion: POLICY_VERSION,
      provenance: provenance({ sourceType: SOURCE_TYPE.CONFIRMED, sourceRecordIds: [], asOf: now }),
      caveats: ['No investable holdings are recorded yet.']
    };
  }

  const total = money.sum(investable.map((asset) => asset.value), currency);
  const growth = money.sum(
    investable.filter((asset) => GROWTH_ORIENTED.includes(asset.assetType)).map((a) => a.value),
    currency
  );
  const defensive = money.sum(
    investable.filter((asset) => DEFENSIVE.includes(asset.assetType)).map((a) => a.value),
    currency
  );

  const growthShare = money.isZero(total) ? null : money.ratio(growth, total);

  const concentrations = investable
    .filter((asset) => !money.isZero(total) &&
      asset.value.minorUnits / total.minorUnits > CONCENTRATION_THRESHOLD)
    .map((asset) => ({
      localId: asset.localId,
      label: asset.label,
      value: asset.value,
      share: asset.value.minorUnits / total.minorUnits
    }));

  const alignment = alignmentFor(growthShare, riskProfile);

  return {
    currency,
    // Concentration outranks alignment: a portfolio that is 80% one holding is
    // worth flagging whether or not the overall mix matches the profile.
    status: concentrations.length > 0 ? PORTFOLIO_STATUS.CONCENTRATED : alignment.status,
    total,
    growth,
    defensive,
    growthShare,
    concentrations,
    alignment,
    policyVersion: POLICY_VERSION,
    provenance: provenance({
      sourceType: SOURCE_TYPE.CONFIRMED,
      sourceRecordIds: investable.map((asset) => asset.localId),
      asOf: now
    }),
    caveats: caveatsFor({ investable, assets, concentrations, alignment, riskProfile })
  };
}

/**
 * Whether the mix suits the person — or that this cannot be said.
 *
 * The UNKNOWN branch is the important one. Grading a portfolio against an
 * assumed "moderate" profile would produce a verdict that looks identical to a
 * real one, and could tell a cautious investor their equity-heavy holdings are
 * fine.
 */
function alignmentFor(growthShare, riskProfile) {
  if (!riskProfile || !riskProfile.known) {
    return {
      status: PORTFOLIO_STATUS.UNKNOWN,
      note:
        riskProfile?.reason ??
        'No confirmed risk profile, so whether this mix suits you cannot be said.'
    };
  }

  // Capacity binds, not tolerance. Somebody willing to take more risk than their
  // finances can absorb should be measured against what they can absorb.
  const binding = bindingLevel(riskProfile);
  const band = SUGGESTED_GROWTH_SHARE[binding];

  if (!band || growthShare === null) {
    return {
      status: PORTFOLIO_STATUS.UNKNOWN,
      note: 'Not enough is known to compare your mix against your profile.'
    };
  }

  if (growthShare < band.min) {
    return {
      status: PORTFOLIO_STATUS.REVIEW,
      note:
        'Your holdings lean more defensive than your profile would suggest. That ' +
        'may be deliberate.'
    };
  }
  if (growthShare > band.max) {
    return {
      status: PORTFOLIO_STATUS.REVIEW,
      note:
        'Your holdings lean more towards growth than your profile would suggest, ' +
        'which means larger swings in value.'
    };
  }
  return {
    status: PORTFOLIO_STATUS.ALIGNED,
    note: 'Your mix sits within the range your profile would suggest.'
  };
}

function bindingLevel(riskProfile) {
  const rank = { LOW: 0, MODERATE: 1, HIGH: 2 };
  const tolerance = riskProfile.tolerance;
  const capacity = riskProfile.capacity;

  if (!tolerance) return capacity;
  if (!capacity) return tolerance;
  return rank[tolerance] <= rank[capacity] ? tolerance : capacity;
}

function caveatsFor({ investable, assets, concentrations, alignment, riskProfile }) {
  const caveats = [];

  if (!riskProfile || !riskProfile.known) {
    caveats.push(
      'Without a confirmed risk profile, this describes the shape of your ' +
      'holdings but cannot say whether it suits you.'
    );
  } else if (riskProfile.isStale) {
    caveats.push(
      'Your risk profile is over two years old. Circumstances change, and it may ' +
      'be worth answering the questions again.'
    );
  }

  if (riskProfile?.mismatch) {
    caveats.push(riskProfile.mismatch.note);
  }

  concentrations.forEach((holding) => {
    caveats.push(
      `${holding.label} is about ${Math.round(holding.share * 100)}% of your ` +
      'investable holdings.'
    );
  });

  const excluded = assets.length - investable.length;
  if (excluded > 0) {
    caveats.push(
      `${excluded} holding(s) — property, vehicles or family-owned — are left out ` +
      'of this mix.'
    );
  }

  caveats.push(
    'This describes how your holdings are spread. It is not a recommendation to ' +
    'buy, sell or switch anything.'
  );

  return caveats;
}

module.exports = {
  assessPortfolio,
  alignmentFor,
  bindingLevel,
  PORTFOLIO_STATUS,
  CONCENTRATION_THRESHOLD,
  GROWTH_ORIENTED,
  DEFENSIVE,
  SUGGESTED_GROWTH_SHARE,
  POLICY_VERSION
};
