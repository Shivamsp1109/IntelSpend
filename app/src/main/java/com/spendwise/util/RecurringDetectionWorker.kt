package com.spendwise.util

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.core.app.NotificationCompat
import com.spendwise.domain.usecase.DetectRecurringPaymentsUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Mentions repeating payments the app has found but never told the user about.
 *
 * Weekly rather than daily, and quiet rather than urgent: nothing here needs
 * acting on today, and a suggestion that interrupts is a suggestion that gets
 * the whole feature muted.
 *
 * Only genuinely new patterns are announced. Detection re-runs over the same
 * history every time, so counting candidates would mean saying "3 payments
 * found" every week for the same three — and saying nothing at all when one is
 * accepted and a different one appears in its place.
 */
@HiltWorker
class RecurringDetectionWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val detectRecurringPayments: DetectRecurringPaymentsUseCase,
    private val reminderState: RecurringReminderState
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        RecurringNotifications.ensureChannels(context)
        if (!RecurringNotifications.canNotify(context)) return Result.success()

        val candidates = runCatching { detectRecurringPayments.once() }
            .getOrElse {
                Log.w(TAG, "Could not scan for recurring payments.", it)
                return Result.retry()
            }

        val signatures = candidates.map { it.signature }.toSet()
        val alreadyAnnounced = reminderState.announcedCandidates()
        val fresh = candidates.filter { it.signature !in alreadyAnnounced }

        // Written whatever happens, so signatures for candidates that have since
        // been accepted or dismissed stop being carried around — and so a
        // pattern that reappears later is treated as new again.
        reminderState.markAnnounced(signatures)

        if (fresh.isEmpty()) return Result.success()

        val title =
            if (fresh.size == 1) "Found a recurring payment"
            else "Found ${fresh.size} recurring payments"

        val text = fresh.take(MAX_LISTED).joinToString("\n") { candidate ->
            val amount = CurrencyFormatter.format(candidate.amount, candidate.currency)
            "$amount · ${candidate.merchant} · ${candidate.cadence.label.lowercase()}"
        } + if (fresh.size > MAX_LISTED) "\nand ${fresh.size - MAX_LISTED} more" else ""

        RecurringNotifications.show(
            context = context,
            id = RecurringNotifications.DETECTION_NOTIFICATION_ID,
            notification = RecurringNotifications.build(
                context = context,
                channelId = RecurringNotifications.DETECTION_CHANNEL_ID,
                title = title,
                text = text,
                priority = NotificationCompat.PRIORITY_LOW
            )
        )

        return Result.success()
    }

    private companion object {
        const val TAG = "RecurringDetectionWorker"

        /** Past this the notification is a wall of text nobody reads. */
        const val MAX_LISTED = 4
    }
}
