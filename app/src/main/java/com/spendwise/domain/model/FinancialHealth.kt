package com.spendwise.domain.model

/**
 * A cash-flow view of one period, computed on the device from what this handset
 * holds.
 *
 * **Not the canonical financial assessment.** That lives on the server, reads
 * every domain — assets, structured loans, insurance, a confirmed risk profile —
 * and is what recommendations and the assistant reason over. This is narrower on
 * purpose: it covers recorded transactions, confirmed commitments and goals, and
 * it works with no network, which is the whole reason it exists.
 *
 * The two are related but they do not answer the same question, and nothing
 * should treat them as interchangeable. [debtToIncomeRatio] here divides
 * recorded loan-repayment *transactions* by income — a description of behaviour.
 * The server's debt-service ratio divides scheduled obligations from real loan
 * terms by income, which is what a lender means by the phrase. Both are correct;
 * they are different figures, and presenting one as the other would misinform.
 *
 * Deterministic and free of any model call. Everything here is arithmetic over
 * figures the user can see for themselves, so a wrong number is a bug with a
 * test rather than an unpredictable answer.
 *
 * The separation this type exists to enforce is **actual against projected**.
 * A figure describing money that has already moved and a figure describing
 * money expected to move are different claims, and adding them produces a
 * number that is true of nothing. They are named apart and never summed;
 * anything projected carries `projected` in its name.
 *
 * Likewise **cash due against monthly cost**. [commitmentsStillDue] is what
 * will actually leave the account before this period ends — an annual insurance
 * premium contributes its whole amount in the month it falls and nothing in the
 * other eleven. [monthlyCommitmentLoad] is the amortised run-rate, where that
 * same premium contributes a twelfth every month. Both are useful and they
 * answer different questions, so both are carried rather than one being made to
 * stand in for the other.
 */
data class FinancialHealthSnapshot(
    val periodLabel: String,
    val currency: Currency,
    /**
     * Which build of the on-device arithmetic produced this, shown alongside the
     * figures. Two snapshots that disagree are otherwise indistinguishable from
     * one that is simply wrong.
     */
    val engineVersion: String = OFFLINE_ENGINE_VERSION,
    /** When this was worked out, so a stale screen cannot pass for a live one. */
    val computedAt: Long = System.currentTimeMillis(),
    /**
     * Currencies present in the data but left out of every figure here. The app
     * has no exchange-rate source, so mixing them would be silently wrong; the
     * assessment reports one currency and says what it excluded.
     */
    val excludedCurrencies: List<Currency> = emptyList(),

    // ── Actual: money that has already moved ──────────────────────────────────

    val actualIncome: Double = 0.0,
    /** Consumption. What was spent on something and is gone. */
    val actualSpending: Double = 0.0,
    /** Loan and EMI repayment. A real outflow, but it clears a debt. */
    val actualDebtRepayment: Double = 0.0,
    /** Into savings and investments. Out of the account, still the user's. */
    val actualSavingsAndInvestment: Double = 0.0,

    // ── Projected: money expected to move before the period ends ──────────────

    /**
     * Confirmed commitments falling due between now and the end of the period
     * that have not been paid yet.
     *
     * Excluding what has already been paid is the whole point. A rent payment
     * that has gone out is sitting in [actualSpending]; adding the commitment on
     * top would count the same rent twice and report a household as far deeper
     * underwater than it is.
     */
    val commitmentsStillDue: Double = 0.0,

    // ── Commitment load: the amortised view ───────────────────────────────────

    /** What every live commitment costs in an average month, cadence-normalised. */
    val monthlyCommitmentLoad: Double = 0.0,
    /** The part of that load which repays debt rather than buying anything. */
    val monthlyDebtCommitmentLoad: Double = 0.0,

    // ── Composition ───────────────────────────────────────────────────────────

    /** Spending in categories treated as essential — see [EssentialClassification]. */
    val essentialExpense: Double = 0.0,
    val discretionaryExpense: Double = 0.0,

    // ── Goals ─────────────────────────────────────────────────────────────────

    val goals: List<GoalFeasibility> = emptyList(),

    // ── Context ───────────────────────────────────────────────────────────────

    /** Live commitments falling due soon, soonest first. */
    val upcomingPayments: List<RecurringEntry> = emptyList(),
    /** Findings from the existing insight engine, not recomputed here. */
    val trendFlags: List<Insight> = emptyList(),
    /**
     * Everything the reader needs to know before trusting a figure above:
     * assumptions made, data missing, classifications that are the app's
     * default rather than the user's own choice.
     */
    val caveats: List<String> = emptyList()
) {
    /** Money that actually left, whatever it was for. */
    val actualOutflow: Double
        get() = actualSpending + actualDebtRepayment + actualSavingsAndInvestment

    /**
     * The real change in the account balance: everything in, less everything
     * out. Negative means the household drew down its balance this period, even
     * if some of what left went into savings.
     */
    val cashFlowSurplus: Double get() = actualIncome - actualOutflow

    /**
     * What is left after the obligations, before any voluntary decision to save
     * or invest. This is the figure a "can I afford it" question needs — putting
     * money into a fund is a choice that can be revisited, paying rent is not.
     */
    val unallocatedSurplus: Double
        get() = actualIncome - actualSpending - actualDebtRepayment

    /** [cashFlowSurplus] once the commitments still to be paid are taken off. */
    val projectedCashFlowSurplus: Double get() = cashFlowSurplus - commitmentsStillDue

    /** [unallocatedSurplus] once the commitments still to be paid are taken off. */
    val projectedUnallocatedSurplus: Double get() = unallocatedSurplus - commitmentsStillDue

    /**
     * Share of income that went into savings and investments.
     *
     * Null rather than zero when no income was recorded. Zero would read as
     * "saved nothing", which is a claim about behaviour; null says the question
     * cannot be answered from what was recorded, which is the truth.
     */
    val savedShareOfIncome: Double?
        get() = if (actualIncome <= 0.0) null else actualSavingsAndInvestment / actualIncome

    /**
     * Debt repayment as a share of income — the ratio a lender would look at.
     * Null when there is no income to compare against.
     */
    val debtToIncomeRatio: Double?
        get() = if (actualIncome <= 0.0) null else actualDebtRepayment / actualIncome

    /** Share of income already spoken for by live commitments. */
    val committedShareOfIncome: Double?
        get() = if (actualIncome <= 0.0) null else monthlyCommitmentLoad / actualIncome

    /** Share of consumption that went on essentials; null when nothing was spent. */
    val essentialShare: Double?
        get() = (essentialExpense + discretionaryExpense)
            .takeIf { it > 0.0 }
            ?.let { essentialExpense / it }

    /**
     * Whether every goal that looks affordable on its own is affordable together.
     *
     * Asked of the set rather than each goal because three goals can each need a
     * comfortable ₹5,000 a month against a ₹12,000 surplus and be impossible in
     * combination. Nothing that looks at one goal at a time can see that.
     */
    val goalsCollectivelyFeasible: Boolean
        get() = totalRequiredMonthlyContribution <= projectedUnallocatedSurplus

    /** What meeting every unfinished goal on time would cost each month. */
    val totalRequiredMonthlyContribution: Double
        get() = goals.filter { it.status.needsContribution }
            .sumOf { it.requiredMonthlyContribution }
}

/**
 * Bumped when the on-device arithmetic changes what it produces from the same
 * inputs, so a figure computed under an older build can be told apart from one
 * computed now.
 */
const val OFFLINE_ENGINE_VERSION: String = "offline-1.0.0"

/** Where a goal stands against its deadline. */
enum class GoalStatus {
    /** Already saved for in full. */
    COMPLETED,

    /** The deadline has passed and it was not met. */
    OVERDUE,

    /** Due within the current month — the whole shortfall is needed now. */
    DUE_THIS_MONTH,

    /** The monthly amount needed fits inside what is left over. */
    ON_TRACK,

    /** The monthly amount needed exceeds what is left over. */
    AT_RISK;

    /** Whether this goal still requires money each month to be met. */
    val needsContribution: Boolean get() = this != COMPLETED
}

/**
 * What one goal needs, and whether that is realistic.
 *
 * [individuallyFeasible] deliberately answers only "on its own". The question
 * of whether the goals work as a set belongs to the snapshot, because it cannot
 * be answered one goal at a time.
 */
data class GoalFeasibility(
    val goal: Goal,
    val requiredMonthlyContribution: Double,
    val monthsRemaining: Int,
    val status: GoalStatus,
    val individuallyFeasible: Boolean
) {
    val shortfall: Double get() = (goal.targetAmount - goal.currentSaved).coerceAtLeast(0.0)
}

/**
 * Which categories count as essential when nothing else is known.
 *
 * A default, and stated as one everywhere it is used. Whether eating out is
 * essential depends on whose life it is, and the app has no standing to decide
 * that — a household with no kitchen and a household with a restaurant habit
 * produce the same rows here. The classification earns its place by making the
 * split visible at all, not by being right about any particular person, so
 * every figure derived from it ships with a caveat saying so.
 */
object EssentialClassification {

    private val ESSENTIAL = setOf(
        ExpenseCategory.RentHousing,
        ExpenseCategory.Utilities,
        ExpenseCategory.MobileInternet,
        ExpenseCategory.Groceries,
        ExpenseCategory.HealthMedical,
        ExpenseCategory.Insurance,
        ExpenseCategory.Education,
        ExpenseCategory.Transport,
        ExpenseCategory.Fuel,
        ExpenseCategory.KidsFamily,
        ExpenseCategory.TaxesGovernment
    )

    fun isEssential(category: ExpenseCategory): Boolean = category in ESSENTIAL

    /** The caveat that must accompany any figure derived from this split. */
    const val CAVEAT: String =
        "The essential and discretionary split uses the app's default view of " +
            "each category, not your own — treat it as a starting point."
}
