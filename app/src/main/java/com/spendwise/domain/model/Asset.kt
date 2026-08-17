package com.spendwise.domain.model

/**
 * Something the user owns, and what it is worth.
 *
 * Deliberately carries no expected return or risk rating. An asset is "a mutual
 * fund holding worth ₹4,00,000 as of 12 August" — that is a fact the user can
 * check. "A mutual fund that returns 11%" is a dated assumption about an asset
 * class, it changes without the holding changing, and storing it here would let
 * a projection present it as something they told us. Expected returns belong to
 * versioned assumption sets and are applied when a projection is run.
 */
data class Asset(
    val id: Int = 0,
    val label: String,
    val type: AssetType,
    val currentValue: Double,
    val currency: Currency = Currency.INR,
    /**
     * When that value was last true. Carried because a holding valued eight
     * months ago says little about today, and the assessment says so rather
     * than presenting a stale figure as current.
     */
    val valuationDate: Long,
    val liquidity: LiquidityClass,
    /**
     * When a lock-in ends, if there is one. Until it passes the holding is not
     * counted as an emergency reserve however liquid its class says it is.
     */
    val lockInUntil: Long? = null,
    val ownership: AssetOwnership = AssetOwnership.SELF,
    val verificationSource: AssetVerification = AssetVerification.MANUAL,
    /** The institution or account, for the user's own recognition. Never parsed. */
    val accountType: String? = null,
    val isSynced: Boolean = false
) {
    /** Whether this could actually be spent in an emergency, as of [now]. */
    fun isReachableNow(now: Long = System.currentTimeMillis()): Boolean =
        ownership != AssetOwnership.FAMILY &&
            liquidity.countsAsReserve &&
            (lockInUntil == null || lockInUntil <= now)
}

enum class AssetType(val label: String) {
    CASH("Cash"),
    BANK_ACCOUNT("Bank account"),
    FIXED_DEPOSIT("Fixed deposit"),
    RECURRING_DEPOSIT("Recurring deposit"),
    MUTUAL_FUND("Mutual fund"),
    STOCK("Shares"),
    BOND("Bonds"),
    ETF("ETF"),
    PROVIDENT_FUND("Provident fund"),
    PENSION("Pension"),
    NPS("NPS"),
    GOLD("Gold"),
    REAL_ESTATE("Property"),
    VEHICLE("Vehicle"),
    INSURANCE_CASH_VALUE("Insurance cash value"),
    CRYPTO("Crypto"),
    LOAN_GIVEN("Money lent out"),
    OTHER("Other");

    companion object {
        fun fromName(name: String): AssetType =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } ?: OTHER
    }
}

/**
 * Whether the money can actually be reached, which is a different question from
 * what kind of asset it is.
 *
 * Kept apart from [AssetType] on purpose: a fixed deposit and a five-year
 * tax-saving deposit are both FIXED_DEPOSIT, and only one of them can be reached
 * in an emergency. Inferring liquidity from the type would count locked money as
 * a reserve — the error that makes a reserve figure dangerous rather than merely
 * wrong, because it fails at exactly the moment it is relied on.
 */
enum class LiquidityClass(val label: String, val description: String) {
    LIQUID_CASH("Available now", "Cash or a current account you can draw on today"),
    LIQUID_INVESTMENT("A few days", "Can be sold and settled within days"),
    ILLIQUID_INVESTMENT("Weeks or months", "Would take a while to turn into cash"),
    PHYSICAL("Must be sold", "Property, gold or a vehicle you would have to sell"),
    RETIREMENT_LOCKED("Locked away", "Retirement money you cannot reach without a penalty");

    /** Whether this can count towards an emergency reserve at all. */
    val countsAsReserve: Boolean
        get() = this == LIQUID_CASH || this == LIQUID_INVESTMENT

    companion object {
        fun fromName(name: String): LiquidityClass =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
                ?: ILLIQUID_INVESTMENT
    }
}

/**
 * Whose it is.
 *
 * Family money the user cannot unilaterally spend is excluded from their net
 * worth and their reserve rather than counted at some fraction — a share of
 * somebody else's holding is not a figure this app can derive, and inventing one
 * would be worse than leaving it out and saying so.
 */
enum class AssetOwnership(val label: String) {
    SELF("Mine"),
    JOINT("Joint"),
    FAMILY("Family's");

    companion object {
        fun fromName(name: String): AssetOwnership =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } ?: SELF
    }
}

/** How the figure got here, so a typed estimate can be weighed differently. */
enum class AssetVerification(val label: String) {
    MANUAL("Entered by you"),
    IMPORTED("From a statement"),
    CONFIRMED("Confirmed balance");

    companion object {
        fun fromName(name: String): AssetVerification =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } ?: MANUAL
    }
}
