package com.spendwise.domain.usecase

import com.spendwise.domain.model.AnalyticsPeriod
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.EssentialClassification
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.FinancialHealthSnapshot
import com.spendwise.domain.model.Goal
import com.spendwise.domain.model.GoalFeasibility
import com.spendwise.domain.model.GoalStatus
import com.spendwise.domain.model.Insight
import com.spendwise.domain.model.RecurringEntry
import com.spendwise.domain.model.RecurringSchedule.toLocalDate
import com.spendwise.domain.model.TransactionNature
import com.spendwise.domain.repository.AnalyticsRepository
import com.spendwise.domain.repository.GoalRepository
import com.spendwise.domain.repository.RecurringEntryRepository
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Assembles everything the app can say about a household's position, from
 * recorded transactions and confirmed commitments alone.
 *
 * Reads through the existing analytics summary rather than querying for its own
 * totals, so the health screen and the analytics screen can never report
 * different figures for the same month — the commonest way two screens in one
 * app come to disagree is each computing the same number its own way.
 *
 * Nothing here infers or adjusts a figure the user has agreed to. A commitment
 * with a price change waiting is counted at the amount they confirmed, and the
 * unanswered change is raised as a caveat instead.
 */
class ComputeFinancialHealthUseCase @Inject constructor(
    private val getSpendingSummaryUseCase: GetSpendingSummaryUseCase,
    private val analyticsRepository: AnalyticsRepository,
    private val recurringEntryRepository: RecurringEntryRepository,
    private val goalRepository: GoalRepository
) {
    suspend operator fun invoke(
        period: AnalyticsPeriod = AnalyticsPeriod.thisMonth(),
        currency: Currency? = null,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): FinancialHealthSnapshot {
        val analytics = getSpendingSummaryUseCase(period, currency)
        val natureTotals = analyticsRepository.totalsByNature(period, analytics.currency)
        val commitments = recurringEntryRepository.observeRecurring().first()
        val goals = goalRepository.observeGoals().first()

        return assemble(
            period = period,
            currency = analytics.currency,
            excludedCurrencies = analytics.excludedCurrencies,
            income = analytics.summary.totalIncome,
            natureTotals = natureTotals,
            spendByCategory = analytics.byCategory,
            commitments = commitments,
            goals = goals,
            trendFlags = analytics.insights,
            now = now,
            zone = zone
        )
    }

    companion object {

        /** How far ahead the screen lists what is coming. */
        private const val UPCOMING_DAYS = 14L

        private const val MILLIS_PER_DAY = 86_400_000L

        /**
         * The arithmetic, with no repository behind it, so every rule below can
         * be tested against figures written by hand rather than a database.
         */
        fun assemble(
            period: AnalyticsPeriod,
            currency: Currency,
            excludedCurrencies: List<Currency>,
            income: Double,
            natureTotals: Map<TransactionNature, Double>,
            spendByCategory: Map<ExpenseCategory, Double>,
            commitments: List<RecurringEntry>,
            goals: List<Goal>,
            trendFlags: List<Insight>,
            now: Long,
            zone: ZoneId = ZoneId.systemDefault()
        ): FinancialHealthSnapshot {
            val range = period.range(zone)

            val spending = natureTotals[TransactionNature.Spending] ?: 0.0
            val debtRepayment = natureTotals[TransactionNature.LoanRepayment] ?: 0.0
            val savingsAndInvestment = (natureTotals[TransactionNature.Savings] ?: 0.0) +
                (natureTotals[TransactionNature.Investment] ?: 0.0)

            // The window for what is still to come starts at whichever is later:
            // now, or the period's start. A period already over has nothing left
            // to project; a future one is projected in full.
            val projection = CommitmentProjection.of(
                entries = commitments,
                from = maxOf(now, range.start),
                until = range.end,
                zone = zone
            )

            val essential = spendByCategory
                .filterKeys { EssentialClassification.isEssential(it) }
                .values.sum()
            val discretionary = spendByCategory
                .filterKeys { !EssentialClassification.isEssential(it) }
                .values.sum()

            val snapshot = FinancialHealthSnapshot(
                periodLabel = period.displayLabel(zone),
                currency = currency,
                excludedCurrencies = excludedCurrencies,
                actualIncome = income,
                actualSpending = spending,
                actualDebtRepayment = debtRepayment,
                actualSavingsAndInvestment = savingsAndInvestment,
                commitmentsStillDue = projection.stillDue,
                monthlyCommitmentLoad = CommitmentProjection.monthlyLoad(commitments),
                monthlyDebtCommitmentLoad = CommitmentProjection.monthlyDebtLoad(commitments),
                essentialExpense = essential,
                discretionaryExpense = discretionary,
                upcomingPayments = upcomingFrom(commitments, now),
                trendFlags = trendFlags
            )

            // Goals are measured against what is left once this period's
            // obligations are met, which is why this needs the snapshot above
            // rather than being computed alongside it.
            val available = snapshot.projectedUnallocatedSurplus
            val assessed = goals
                .map { feasibilityOf(it, available, now, zone) }
                .sortedWith(compareBy({ it.status.ordinal }, { -it.requiredMonthlyContribution }))

            // Caveats read the finished snapshot, goals included. Deriving them
            // from the half-built one above answered every question about goals
            // against an empty list, so the warning that they are affordable
            // apart but not together could never fire.
            val withGoals = snapshot.copy(goals = assessed)
            return withGoals.copy(
                caveats = caveatsFor(withGoals, projection, income, period, zone)
            )
        }

        /**
         * Where one goal stands, given what is left over each month.
         *
         * A deadline inside the current month collapses to needing the whole
         * remaining shortfall now — dividing by a fraction of a month would
         * report a required contribution larger than the goal itself.
         */
        fun feasibilityOf(
            goal: Goal,
            availableMonthly: Double,
            now: Long,
            zone: ZoneId = ZoneId.systemDefault()
        ): GoalFeasibility {
            val shortfall = (goal.targetAmount - goal.currentSaved).coerceAtLeast(0.0)
            if (shortfall <= 0.0) {
                return GoalFeasibility(
                    goal = goal,
                    requiredMonthlyContribution = 0.0,
                    monthsRemaining = 0,
                    status = GoalStatus.COMPLETED,
                    individuallyFeasible = true
                )
            }

            val today = now.toLocalDate(zone)
            val target = goal.targetDate.toLocalDate(zone)

            val overdue = target.isBefore(today)
            val dueThisMonth = !overdue && YearMonth.from(target) == YearMonth.from(today)

            val monthsRemaining = when {
                overdue || dueThisMonth -> 0
                else -> ChronoUnit.MONTHS.between(YearMonth.from(today), YearMonth.from(target))
                    .toInt()
                    .coerceAtLeast(1)
            }

            // Overdue and due-this-month both need the whole shortfall now;
            // there is no run of months left to spread it over.
            val required = if (monthsRemaining <= 0) shortfall else shortfall / monthsRemaining
            val feasible = required <= availableMonthly

            return GoalFeasibility(
                goal = goal,
                requiredMonthlyContribution = required,
                monthsRemaining = monthsRemaining,
                status = when {
                    overdue -> GoalStatus.OVERDUE
                    dueThisMonth -> GoalStatus.DUE_THIS_MONTH
                    feasible -> GoalStatus.ON_TRACK
                    else -> GoalStatus.AT_RISK
                },
                individuallyFeasible = feasible
            )
        }

        private fun upcomingFrom(commitments: List<RecurringEntry>, now: Long): List<RecurringEntry> {
            val horizon = now + UPCOMING_DAYS * MILLIS_PER_DAY
            return commitments
                .filter { it.isLive && it.nextDueDate != null && it.nextDueDate in now..horizon }
                .sortedBy { it.nextDueDate }
        }

        /**
         * What the reader has to know before trusting the figures.
         *
         * Written as plain sentences rather than codes because they are shown
         * verbatim, and because an assistant reading this snapshot later needs
         * to be able to repeat them without interpreting anything.
         */
        private fun caveatsFor(
            snapshot: FinancialHealthSnapshot,
            projection: CommitmentProjection.Projection,
            income: Double,
            period: AnalyticsPeriod,
            zone: ZoneId
        ): List<String> = buildList {
            if (income <= 0.0) {
                add(
                    "No income is recorded for ${period.displayLabel(zone)}, so savings " +
                        "rate and debt-to-income cannot be worked out."
                )
            }

            if (snapshot.excludedCurrencies.isNotEmpty()) {
                val names = snapshot.excludedCurrencies.joinToString(", ") { it.code }
                add(
                    "Figures cover ${snapshot.currency.code} only. Activity in " +
                        "$names is not included."
                )
            }

            if (snapshot.essentialExpense + snapshot.discretionaryExpense > 0.0) {
                add(EssentialClassification.CAVEAT)
            }

            if (projection.unschedulable.isNotEmpty()) {
                add(
                    "${projection.unschedulable.size} tracked commitment(s) have no " +
                        "recorded payment yet, so they are not in the projection."
                )
            }

            if (projection.overdueCount > 0) {
                add(
                    "${projection.overdueCount} commitment(s) were due earlier with " +
                        "nothing recorded against them. They are not counted here."
                )
            }

            val pendingPriceChanges = snapshot.upcomingPayments.count { it.hasPendingPriceChange }
            if (pendingPriceChanges > 0) {
                add(
                    "$pendingPriceChanges commitment(s) have a price change waiting on " +
                        "you. Figures use the amount you agreed, not the new one."
                )
            }

            if (snapshot.goals.isNotEmpty() && !snapshot.goalsCollectivelyFeasible &&
                snapshot.goals.all { it.individuallyFeasible }
            ) {
                add(
                    "Each goal is affordable on its own, but not all of them together " +
                        "out of what is left over."
                )
            }
        }
    }
}
