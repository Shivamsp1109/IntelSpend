package com.spendwise.domain.usecase

import com.spendwise.domain.model.RecurringEntry
import com.spendwise.domain.model.RecurringSchedule
import com.spendwise.domain.model.RecurringSchedule.toLocalDate
import com.spendwise.domain.model.TransactionNature
import com.spendwise.domain.model.monthlyEquivalent
import java.time.ZoneId

/**
 * What confirmed commitments will still cost before a window closes.
 *
 * Pure, and separate from the engine that consumes it, because this is the
 * calculation most easily got wrong in a way nobody notices. The tempting
 * version — add up every live commitment and subtract it from the surplus —
 * double-counts every commitment already paid this month, since the payment is
 * sitting in the period's spending total too. A household paying ₹40,000 of
 * rent and EMI would be reported ₹40,000 worse off than it is, every month,
 * and the figure would look plausible enough to believe.
 *
 * The projection therefore walks each commitment's actual due dates. A payment
 * already made moved `nextDueDate` past it when it was reconciled, so it simply
 * is not in the window any more and cannot be counted twice.
 */
object CommitmentProjection {

    /**
     * A long-abandoned commitment could otherwise walk forward hundreds of
     * times. Well past any real cadence within a period, and bounded.
     */
    private const val MAX_OCCURRENCES = 64

    /**
     * @param from   Start of the window. Occurrences before it are reported as
     *               overdue rather than counted, so a missed payment is neither
     *               silently dropped nor quietly added to this period's bill.
     * @param until  End of the window, inclusive.
     */
    fun of(
        entries: List<RecurringEntry>,
        from: Long,
        until: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Projection {
        var stillDue = 0.0
        var debtStillDue = 0.0
        var occurrences = 0
        var overdue = 0.0
        var overdueCount = 0
        val unschedulable = mutableListOf<RecurringEntry>()

        for (entry in entries.filter { it.isLive }) {
            val firstDue = entry.nextDueDate
            if (firstDue == null) {
                // Nothing has been observed paying this yet, so there is no
                // anchor to project from. Reported rather than assumed: guessing
                // a due date would put a figure into the total that no payment
                // supports.
                unschedulable += entry
                continue
            }

            var due: Long = firstDue
            var steps = 0
            while (due <= until && steps < MAX_OCCURRENCES) {
                if (due >= from) {
                    stillDue += entry.amount
                    if (entry.nature == TransactionNature.LoanRepayment) {
                        debtStillDue += entry.amount
                    }
                    occurrences++
                } else {
                    overdue += entry.amount
                    overdueCount++
                }

                due = RecurringSchedule.nextDueDateMillis(
                    lastOccurrence = due,
                    cadence = entry.cadence,
                    dueDayOfMonth = entry.dueDayOfMonth ?: due.toLocalDate(zone).dayOfMonth,
                    zone = zone
                )
                steps++
            }
        }

        return Projection(
            stillDue = stillDue,
            debtStillDue = debtStillDue,
            occurrences = occurrences,
            overdueAmount = overdue,
            overdueCount = overdueCount,
            unschedulable = unschedulable
        )
    }

    /**
     * What every live commitment costs in an average month.
     *
     * Cadence-normalised before summing. A weekly ₹1,000 and a yearly ₹12,000
     * added as ₹13,000 of monthly obligation is wrong by a factor of four in
     * one direction and twelve in the other.
     */
    fun monthlyLoad(entries: List<RecurringEntry>): Double =
        entries.filter { it.isLive }.sumOf { it.monthlyEquivalent }

    /** The part of [monthlyLoad] that repays debt rather than buying anything. */
    fun monthlyDebtLoad(entries: List<RecurringEntry>): Double =
        entries.filter { it.isLive && it.nature == TransactionNature.LoanRepayment }
            .sumOf { it.monthlyEquivalent }

    data class Projection(
        /** Cash expected to leave the account inside the window. */
        val stillDue: Double,
        /** The debt-repayment part of [stillDue]. */
        val debtStillDue: Double,
        val occurrences: Int,
        /** Payments whose due date has passed with nothing recorded against them. */
        val overdueAmount: Double,
        val overdueCount: Int,
        /** Live commitments with no observed payment to project from. */
        val unschedulable: List<RecurringEntry>
    )
}
