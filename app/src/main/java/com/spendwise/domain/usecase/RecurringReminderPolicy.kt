package com.spendwise.domain.usecase

import com.spendwise.domain.model.RecurringEntry
import java.time.Instant
import java.time.ZoneId

/**
 * Decides which commitments are worth interrupting someone about.
 *
 * The whole value of a payment reminder is that it is trustworthy. One
 * notification about a bill the user paid last week teaches them the feature is
 * noise, and they turn it off — after which the genuinely useful reminders never
 * arrive either. So the bar for firing is deliberately high, and every rule here
 * exists to remove a specific way of being wrong.
 */
object RecurringReminderPolicy {

    /**
     * How far ahead a payment is worth mentioning.
     *
     * Long enough to move money if the account is short, short enough that the
     * reminder still feels connected to the payment.
     */
    const val LEAD_DAYS = 3L

    private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

    /**
     * Commitments to remind about now.
     *
     * @param entries live commitments with a projected due date
     * @param lastRemindedDueDate what this device has already given notice of,
     *   per commitment id
     */
    fun due(
        entries: List<RecurringEntry>,
        lastRemindedDueDate: (Int) -> Long?,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<RecurringEntry> {
        val today = startOfToday(now, zone)

        return entries
            .filter { it.isLive }
            .filter { entry ->
                val dueDate = entry.nextDueDate ?: return@filter false

                // Only ahead of time, never after. A payment that looks overdue
                // is just as likely to have been made and not yet imported —
                // bank statements arrive in batches, weeks late — and "your rent
                // is overdue" to someone who paid it on time is the single
                // fastest way to lose their trust in every other reminder.
                if (dueDate < today) return@filter false
                if (dueDate > now + LEAD_DAYS * DAY_MILLIS) return@filter false

                // Once per cycle. The worker runs daily, and a bill three days
                // out would otherwise be announced three times before it is even
                // paid.
                lastRemindedDueDate(entry.id) != dueDate
            }
            .sortedBy { it.nextDueDate }
    }

    /**
     * Local midnight, not UTC midnight.
     *
     * Due dates are stored as midnight in the user's own zone. Truncating the
     * current time to a UTC day boundary instead would put the cutoff at 05:30
     * local in India, and every payment due today would read as overdue — and so
     * be silently suppressed — from breakfast onwards.
     */
    private fun startOfToday(now: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
}
