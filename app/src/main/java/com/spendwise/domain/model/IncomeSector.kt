package com.spendwise.domain.model

/**
 * Top-level grouping for income types.
 * Each sector groups a set of [IncomeSource] entries.
 */
enum class IncomeSector(val displayName: String) {
    ACTIVE("Active Income"),
    CAPITAL_INVESTMENT("Capital & Investment"),
    RENTAL_PROPERTY("Rental & Property"),
    BUSINESS_PASSIVE("Business & Passive Income"),
    TRANSFER_GOVT("Transfer & Govt Payments"),
    MISCELLANEOUS("Miscellaneous")
}
