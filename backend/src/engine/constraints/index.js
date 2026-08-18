/**
 * The hard constraints, and what a constraint is.
 *
 * A constraint is a gate, not a warning. It runs before a candidate can be
 * offered at all, and a candidate that fails one is rejected outright rather
 * than presented with a caution attached — because a caution beside an
 * attractive number is a caution most people will read past.
 *
 * Each lives in its own file behind the same shape:
 *
 *   { id, version, appliesTo(candidate), evaluate(candidate, context) }
 *
 * `evaluate` returns `{ passed, reason, detail }`. It never throws on missing
 * data: absent information is a reason to refuse, and a constraint that crashed
 * when a domain was empty would take the whole assessment down with it.
 *
 * The version on each matters as much as the logic. A trace read next year has
 * to be able to say which rules were in force when a recommendation was made,
 * and a constraint whose behaviour changed without its version changing makes
 * that unanswerable.
 */
const protectEmergencyReserve = require('./protectEmergencyReserve');
const noWorseningDeficit = require('./noWorseningDeficit');
const requireRiskProfile = require('./requireRiskProfile');
const noCurrencyMixing = require('./noCurrencyMixing');
const requireComponentReadiness = require('./requireComponentReadiness');

/**
 * Order is not arbitrary. The cheapest and most fundamental checks run first, so
 * a candidate that fails on missing data does not also get evaluated against
 * arithmetic built on that missing data.
 */
const HARD_CONSTRAINTS = Object.freeze([
  requireComponentReadiness,
  noCurrencyMixing,
  requireRiskProfile,
  protectEmergencyReserve,
  noWorseningDeficit
]);

/** The versions in force, recorded on every trace. */
function constraintVersions() {
  return HARD_CONSTRAINTS.reduce((versions, constraint) => {
    versions[constraint.id] = constraint.version;
    return versions;
  }, {});
}

module.exports = {
  HARD_CONSTRAINTS,
  constraintVersions,
  protectEmergencyReserve,
  noWorseningDeficit,
  requireRiskProfile,
  noCurrencyMixing,
  requireComponentReadiness
};
