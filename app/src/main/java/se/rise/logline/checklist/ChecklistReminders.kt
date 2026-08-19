package se.rise.logline.checklist

import androidx.core.net.toUri
import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import se.rise.logline.MainActivity

/**
 * Per-item reminders, armed with `AlarmManager` and delivered as notifications.
 *
 * **Inexact on purpose.** `setAndAllowWhileIdle` gets through Doze but is allowed to slip by minutes;
 * `setExactAndAllowWhileIdle` would need `SCHEDULE_EXACT_ALARM`, which Android treats as an alarm-clock
 * permission and which the Play policy expects an alarm-clock justification for. "Check the anchor
 * light in twenty minutes" tolerates a few minutes of drift. Something that genuinely could not would
 * be a different feature with a different permission.
 *
 * Nothing here goes on the bus — see [ChecklistReminder].
 */
object ChecklistReminders {

    const val EXTRA_PROCEDURE_ID = "se.rise.logline.checklist.PROCEDURE_ID"
    const val EXTRA_ITEM_ID = "se.rise.logline.checklist.ITEM_ID"

    private const val CHANNEL_ID = "checklist_reminders"

    fun arm(context: Context, reminder: ChecklistReminder) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        manager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            reminder.dueAtEpochMillis,
            firePendingIntent(context, reminder),
        )
    }

    fun cancel(context: Context, reminder: ChecklistReminder) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        manager.cancel(firePendingIntent(context, reminder))
        NotificationManagerCompat.from(context).cancel(reminder.notificationId())
    }

    /**
     * Re-arm everything. Used after a reboot, when `AlarmManager` has forgotten the lot, and after any
     * edit — arming an alarm that is already armed replaces it, so this is safe to call broadly.
     *
     * A one-shot whose time passed while the device was off is armed in the past, which fires it as
     * soon as the alarm is set. That is the honest outcome: the reminder is late, not cancelled.
     */
    fun armAll(context: Context, reminders: List<ChecklistReminder>) {
        reminders.forEach { arm(context, it) }
    }

    /**
     * Post the reminder.
     *
     * Its own channel, separate from the publisher's ongoing notification: one is a service saying it
     * is alive and the other is asking for something to be done, and a person who silences the first
     * must not thereby silence the second.
     */
    fun notify(context: Context, reminder: ChecklistReminder, procedureTitle: String, itemTitle: String) {
        createChannel(context)
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_PROCEDURE_ID, reminder.procedureId)
            putExtra(EXTRA_ITEM_ID, reminder.itemId)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(itemTitle)
            .setContentText(procedureTitle)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    reminder.notificationId(),
                    open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
        // POST_NOTIFICATIONS may be denied — a person can use checklists without ever granting it, and
        // the alarm still fired and the reminder still advanced either way. Checked rather than caught
        // because a denied permission is an ordinary state here, not an exception. Inline rather than
        // behind a helper: lint does not follow the call, and it is right to insist on seeing it.
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        NotificationManagerCompat.from(context).notify(reminder.notificationId(), notification)
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Checklist reminders",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "Reminders for individual checklist items" }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun firePendingIntent(context: Context, reminder: ChecklistReminder): PendingIntent {
        val intent = Intent(context, ChecklistReminderReceiver::class.java).apply {
            action = ChecklistReminderReceiver.ACTION_FIRE
            putExtra(EXTRA_PROCEDURE_ID, reminder.procedureId)
            putExtra(EXTRA_ITEM_ID, reminder.itemId)
            // In the data, not just the extras: two PendingIntents that differ only by extras are the
            // *same* intent as far as AlarmManager is concerned, so without this a second reminder
            // would silently replace the first.
            data = "logline://checklist/${reminder.procedureId}/${reminder.itemId}".toUri()
        }
        return PendingIntent.getBroadcast(
            context,
            reminder.notificationId(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** Stable, positive, and derived from the item — so a re-arm addresses the same slot. */
internal fun ChecklistReminder.notificationId(): Int = key.hashCode() and 0x7fffffff
