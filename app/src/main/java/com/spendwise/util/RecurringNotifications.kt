package com.spendwise.util

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.spendwise.MainActivity
import com.spendwise.R

/**
 * Shared plumbing for the recurring-payment notifications.
 *
 * Two channels rather than one, because the two say very different things. A
 * payment falling due is time-sensitive and worth a heads-up; a suggestion that
 * the app has spotted a pattern is not, and putting them together would force
 * the user to silence both to be rid of the quieter one.
 */
object RecurringNotifications {

    const val DUE_CHANNEL_ID = "recurring_due_reminder"
    const val DETECTION_CHANNEL_ID = "recurring_detection"

    /** Fixed ids so a fresh notification replaces the last rather than stacking. */
    const val DUE_NOTIFICATION_ID = 2001
    const val DETECTION_NOTIFICATION_ID = 2002

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                DUE_CHANNEL_ID,
                "Upcoming payments",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Rent, EMIs and subscriptions falling due soon" }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                DETECTION_CHANNEL_ID,
                "Recurring payments found",
                // Lower, on purpose: this is a suggestion the user can look at
                // whenever, not something that needs their attention now.
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "When the app spots a repeating payment in your history" }
        )
    }

    /**
     * Whether notifications can actually be shown.
     *
     * Checked before doing the work rather than after: building a notification
     * the system will discard wastes a database read on every scheduled run.
     */
    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    fun build(
        context: Context,
        channelId: String,
        title: String,
        text: String,
        priority: Int = NotificationCompat.PRIORITY_DEFAULT
    ): Notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(title)
        .setContentText(text)
        // Long text is truncated to one line otherwise, which for several
        // payments at once would show the first and hide the rest.
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setPriority(priority)
        .setAutoCancel(true)
        .setContentIntent(openApp(context))
        .build()

    /**
     * Opens the app when tapped.
     *
     * Deliberately the ordinary launch intent rather than a deep link into the
     * recurring screen: the app decides where to land based on auth state, and a
     * notification that dropped an unauthenticated user onto a data screen would
     * either show nothing or bounce them straight out.
     */
    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Posts a notification, if that is still allowed.
     *
     * The permission is re-checked here rather than trusted from the caller, and
     * the SecurityException is caught explicitly: the user can revoke
     * notifications between a worker starting and reaching this line, and a
     * background job that dies for that reason would take its real work — the
     * reconciliation it just did — down with it.
     */
    fun show(context: Context, id: Int, notification: Notification) {
        if (!canNotify(context)) return
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (error: SecurityException) {
            Log.w(TAG, "Notification permission went away before posting.", error)
        }
    }

    private const val TAG = "RecurringNotifications"
}
