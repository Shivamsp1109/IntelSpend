package com.spendwise.domain.model

/**
 * What a transaction was for.
 *
 * Widened from an original seven, which was the main reason so much landed in
 * [Other]: groceries, rent, fuel, subscriptions and school fees had no home, so
 * a correctly-read merchant still ended up uncategorised and the analytics
 * screen showed one enormous slice that said nothing.
 *
 * The size is a deliberate middle. Too few and everything collapses into Other;
 * too many and no single category holds enough to be worth looking at. These are
 * the divisions an Indian household actually budgets along.
 *
 * Note this says what the money was *for*, not whether it was spending at all —
 * see [TransactionNature], which is why there is no "Transfer" or "EMI" here.
 * Those are movements of money, and folding them in as categories would leave
 * them counted in the monthly total.
 */
enum class ExpenseCategory(val label: String) {
    FoodDining("Food & Dining"),
    Groceries("Groceries"),
    Transport("Transport"),
    Fuel("Fuel"),
    Travel("Travel"),
    Shopping("Shopping"),
    RentHousing("Rent & Housing"),
    Utilities("Utilities"),
    MobileInternet("Mobile & Internet"),
    Subscriptions("Subscriptions"),
    Entertainment("Entertainment"),
    HealthMedical("Health & Medical"),
    Insurance("Insurance"),
    Education("Education"),
    PersonalCare("Personal Care"),
    HomeHousehold("Home & Household"),
    GiftsDonations("Gifts & Donations"),
    KidsFamily("Kids & Family"),
    Pets("Pets"),
    TaxesGovernment("Taxes & Government"),
    BankFees("Bank Fees & Charges"),
    Other("Other");

    companion object {

        /**
         * Labels this app used to store, and where those transactions belong now.
         *
         * The category column holds the display label rather than the enum name,
         * so renaming "Food" to "Food & Dining" would leave every existing row
         * matching nothing and falling to Other — silently reclassifying a
         * user's entire history on upgrade.
         *
         * "Bills" is the awkward one. It covered utilities, phone bills and
         * subscriptions indiscriminately; Utilities is the closest single home
         * and the least wrong place to land.
         */
        private val LEGACY_LABELS = mapOf(
            "food" to FoodDining,
            "travel" to Travel,
            "shopping" to Shopping,
            "bills" to Utilities,
            "health" to HealthMedical,
            "entertainment" to Entertainment,
            "education" to Education,
            "groceries" to Groceries,
            "other" to Other
        )

        /**
         * Resolves a stored or model-supplied label.
         *
         * Matches the enum name as well as the label, because the learned-category
         * store writes names while the database writes labels — a mismatch there
         * is exactly the kind of thing that quietly sends everything to Other.
         */
        fun fromLabel(label: String): ExpenseCategory {
            val trimmed = label.trim()
            if (trimmed.isEmpty()) return Other

            return entries.firstOrNull { it.label.equals(trimmed, ignoreCase = true) }
                ?: entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
                ?: LEGACY_LABELS[trimmed.lowercase()]
                ?: Other
        }
    }
}
