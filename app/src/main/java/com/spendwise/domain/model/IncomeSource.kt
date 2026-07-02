package com.spendwise.domain.model

/**
 * Fine-grained income source, grouped by [IncomeSector].
 *
 * The [displayTag] renders as "Label / Sector" for compact UI display,
 * e.g. "Salary / Active Income", "Dividends / Capital & Investment".
 *
 * For [MISCELLANEOUS], the [Income.note] field holds the user's free-text
 * description; [label] serves as the fallback display value.
 */
enum class IncomeSource(val label: String, val sector: IncomeSector) {

    // ── Active Income ─────────────────────────────────────────────────────────
    SALARY("Salary", IncomeSector.ACTIVE),
    BONUS("Bonus", IncomeSector.ACTIVE),
    FREELANCING("Freelancing", IncomeSector.ACTIVE),
    GRATUITY("Gratuity", IncomeSector.ACTIVE),
    OTHER_ACTIVE("Other", IncomeSector.ACTIVE),

    // ── Capital & Investment ──────────────────────────────────────────────────
    CAPITAL_GAINS("Capital Gains", IncomeSector.CAPITAL_INVESTMENT),
    DIVIDENDS("Dividends", IncomeSector.CAPITAL_INVESTMENT),
    INTEREST("Interest", IncomeSector.CAPITAL_INVESTMENT),
    P2P_LENDING("P2P Lending", IncomeSector.CAPITAL_INVESTMENT),
    OTHER_CAPITAL("Others", IncomeSector.CAPITAL_INVESTMENT),

    // ── Rental & Property ─────────────────────────────────────────────────────
    RESIDENTIAL_REAL_ESTATE("Residential Real Estate", IncomeSector.RENTAL_PROPERTY),
    COMMERCIAL_REAL_ESTATE("Commercial Real Estate", IncomeSector.RENTAL_PROPERTY),
    ASSET_RENTALS("Asset Rentals", IncomeSector.RENTAL_PROPERTY),
    OTHER_RENTAL("Other", IncomeSector.RENTAL_PROPERTY),

    // ── Business & Passive Income ─────────────────────────────────────────────
    BUSINESS_PROFITS("Business Profits", IncomeSector.BUSINESS_PASSIVE),
    ROYALTIES("Royalties", IncomeSector.BUSINESS_PASSIVE),
    AFFILIATE_MARKETING("Affiliate Marketing", IncomeSector.BUSINESS_PASSIVE),
    DIGITAL_PRODUCTS("Digital Products", IncomeSector.BUSINESS_PASSIVE),
    OTHER_BUSINESS("Other", IncomeSector.BUSINESS_PASSIVE),

    // ── Transfer & Govt Payments ──────────────────────────────────────────────
    PENSION("Pension", IncomeSector.TRANSFER_GOVT),
    SOCIAL_SECURITY("Social Security", IncomeSector.TRANSFER_GOVT),
    ALIMONY_CHILD_SUPPORT("Alimony & Child Support", IncomeSector.TRANSFER_GOVT),
    GRANTS_SCHOLARSHIPS("Grants & Scholarships", IncomeSector.TRANSFER_GOVT),
    OTHER_TRANSFER("Other", IncomeSector.TRANSFER_GOVT),

    // ── Miscellaneous ─────────────────────────────────────────────────────────
    /** Free-text entry — populate [Income.note] with the user's description. */
    MISCELLANEOUS("Miscellaneous", IncomeSector.MISCELLANEOUS);

    /** Short display tag, e.g. "Salary / Active Income". */
    val displayTag: String get() = "$label / ${sector.displayName}"

    companion object {
        fun fromName(name: String): IncomeSource =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: MISCELLANEOUS

        /** Returns all sources belonging to a given [sector]. */
        fun bySector(sector: IncomeSector): List<IncomeSource> =
            entries.filter { it.sector == sector }
    }
}
