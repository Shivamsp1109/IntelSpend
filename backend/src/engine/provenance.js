/**
 * Where a figure came from, and whether the engine is allowed to overwrite it.
 *
 * The distinction that matters is CONFIRMED against everything else. This
 * product's standing rule is that anything it infers is a proposal and only
 * what the user has agreed is fact — a detected price change waits for an
 * answer, a dismissed suggestion stays dismissed. Carrying the source on the
 * value itself is what lets that rule be enforced by a check rather than
 * remembered by whoever writes the next engine module.
 */
const SOURCE_TYPE = Object.freeze({
  /** The user entered or explicitly agreed to this. Never overwritten. */
  CONFIRMED: 'CONFIRMED',
  /** Read from a statement, receipt or bank import. Trusted, not agreed. */
  IMPORTED: 'IMPORTED',
  /** The app worked this out from patterns. A proposal, never a fact. */
  INFERRED: 'INFERRED',
  /** Derived by the engine from other values in this same snapshot. */
  CALCULATED: 'CALCULATED'
});

const SOURCE_TYPES = Object.freeze(Object.values(SOURCE_TYPE));

/**
 * @param sourceType    one of SOURCE_TYPE
 * @param sourceRecordIds  the rows this rests on, so a trace can reach them
 * @param asOf          when the underlying data was true, not when it was read
 * @param formulaId     e.g. 'cashflow.netCashFlow.v1', for CALCULATED values
 */
function provenance({ sourceType, sourceRecordIds = [], asOf, formulaId = null }) {
  if (!SOURCE_TYPES.includes(sourceType)) {
    throw new Error(`Unknown provenance source type '${sourceType}'.`);
  }
  if (sourceType === SOURCE_TYPE.CALCULATED && !formulaId) {
    // A derived figure nobody can trace back to a formula is exactly the kind
    // of number this architecture exists to prevent.
    throw new Error('A CALCULATED value must name the formula that produced it.');
  }
  return Object.freeze({
    sourceType,
    sourceRecordIds: Object.freeze([...sourceRecordIds]),
    asOf: asOf ?? null,
    formulaId
  });
}

/** Whether the engine may replace this value without asking the user first. */
function isOverwritable(prov) {
  return prov.sourceType !== SOURCE_TYPE.CONFIRMED;
}

module.exports = { SOURCE_TYPE, SOURCE_TYPES, provenance, isOverwritable };
